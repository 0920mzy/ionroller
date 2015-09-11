package ionroller

import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.elasticbeanstalk.model.{ApplicationVersionDescription, EnvironmentDescription, EventDescription}
import com.amazonaws.services.elasticloadbalancing.model.InstanceState
import com.amazonaws.services.route53.model.ResourceRecordSet
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import com.typesafe.scalalogging.StrictLogging
import ionroller.aws._
import ionroller.stream._
import ionroller.tracking.{Event, ExceptionEvent}
import org.joda.time.DateTime

import scala.collection.JavaConverters._
import scalaz._
import scalaz.concurrent.Task
import scalaz.std.list._
import scalaz.std.map._
import scalaz.std.option._
import scalaz.stream._
import scalaz.syntax.either._
import scalaz.syntax.traverse._
import scalaz.syntax.std.option._

class LiveStateCollector(cache: Kleisli[Task, String, AWSClientCache]) extends StrictLogging {
  import LiveStateCollector._

  /**
   * Creates a process which can retrieve the current live state, and save the value to a supplied signal.
   * The process provides at most one value, run the process again to get a new live state. It also
   * logs exceptions found when attempting to retrieve the state.
   *
   * @param config The current system configuration
   * @return A Process which emits one LiveSystemState message
   */
  def liveState(config: SystemConfiguration): Process[Task, LiveSystemState] = {
    Process.eval(liveSystemState(config).attempt)
      .logThrowable(logger, "Exception when retrieving live state")
  }

  def liveContainerEnvironments(role: String, appVersionDescriptions: Seq[ApplicationVersionDescription], envs: Seq[EnvironmentDescription], events: Map[String, Seq[EventDescription]], asgs: Map[String, AutoScalingGroup]): List[ContainerEnvironment] = {
    for {
      env <- envs.toList
      envEvents = events.getOrElse(env.getEnvironmentName, Seq.empty)
      asg = asgs.get(env.getEnvironmentId)
      containerEnv <- ContainerEnvironment.containerEnvironment(role, appVersionDescriptions, env, envEvents, asg)
    } yield containerEnv
  }

  def gatherDataByRole[A](config: SystemConfiguration)(f: (String, Map[TimelineName, TimelineConfiguration]) => Task[CheckedBy[TimelineName, List[A]]]) = {
    val tasks = for {
      (role, timelines) <- config.timelines.groupBy(_._2.ionrollerRoleArn)
    } yield Task.fork(f(role, timelines))(ionrollerExecutorService)

    Nondeterminism[Task].aggregateCommutative(tasks.toList)
  }

  def liveEnvironmentStateByRole(role: String): AWSCall[Map[TimelineName, List[ContainerEnvironment]]] = {
    logger.debug(s"""Getting live environment state for role $role""")
    for {
      appVersionDescriptions <- ElasticBeanstalk.describeApplicationVersions
      envDescriptions <- ElasticBeanstalk.describeEnvironments
      envEvents <- ElasticBeanstalk.describeEvents(new DateTime().minusMinutes(10).some).map(_.groupBy(evt => evt.getEnvironmentName))
      asgs <- AutoScaling.getAutoScalingGroupDetails(Seq.empty)
      asgMap = asgs
        .filter(_.getTags.asScala.exists(t => t.getKey == "aws:cloudformation:logical-id" && t.getValue == "AWSEBAutoScalingGroup"))
        .groupBy(_.getTags.asScala.find(_.getKey == "elasticbeanstalk:environment-id")).collect {
          case (Some(tagDesc), asgList) => tagDesc.getValue -> asgList.head
        }
      liveEnvs = liveContainerEnvironments(role, appVersionDescriptions, envDescriptions, envEvents, asgMap)
    } yield liveEnvs.groupBy(_.timeline)
  }

  def liveEnvironmentState(c: SystemConfiguration): Task[CheckedBy[TimelineName, List[ContainerEnvironment]]] = {
    gatherDataByRole(c) { (role, timelines) =>
      val envState = (cache andThen liveEnvironmentStateByRole(role)).run(role)
      LiveStateCollector.recoverErrorsForTimelines(envState, timelines.keys.toSeq)
    }
  }

  def groupTargetsByTimeline(rrsList: List[ResourceRecordSet], timelineByUrl: Map[String, TimelineName]): Map[TimelineName, List[String]] = {
    val nameTargetList = for {
      rrs <- rrsList
      targetDnsName <- Route53.getDnsNameFromRRS(rrs)
      timelineName <- timelineByUrl.get(rrs.getName)
    } yield Map(timelineName -> List(targetDnsName))

    nameTargetList.foldMap(identity)
  }

  def listResourceRecordSet(zone: String, config: Map[TimelineName, TimelineConfiguration]): AWSCall[CheckedBy[TimelineName, List[String]]] = {
    val timelineByUrl = config.groupBy(_._2.url + ".").mapValues(_.head._1)

    Route53.listResourceRecordSets(zone)
      .map(l => groupTargetsByTimeline(l, timelineByUrl))
      .mapK(i => recoverErrorsForTimelines(i, config.keys.toSeq))
  }

  def liveDNSStateByRole(timelines: Map[TimelineName, TimelineConfiguration], role: String): AWSCall[CheckedBy[TimelineName, List[String]]] = {
    Kleisli { cache =>
      logger.debug(s"""Getting live DNS data for role $role""")

      val zones = timelines.groupBy(_._2.hostedZoneId)

      val results = for {
        (zone, config) <- zones
        rrs = listResourceRecordSet(zone, config)
      } yield rrs.run(cache)

      Nondeterminism[Task].aggregateCommutative(results.toSeq)
    }
  }

  /**
   * Returns a task which reports where the DNS entry for all services are currently
   * pointing to (or None if it does not exist, or is incorrectly configured).
   *
   * @param config The system configuration
   * @return A Task returning the current DNS state of the system
   */
  def liveDNSState(config: SystemConfiguration): Task[CheckedBy[TimelineName, List[String]]] = {
    gatherDataByRole(config) { (role, timelinesByRole) =>
      (cache andThen liveDNSStateByRole(timelinesByRole, role)).run(role)
    }
  }

  def liveElbState(config: SystemConfiguration): Task[CheckedBy[TimelineName, Seq[InstanceState]]] = {
    val tasks = for {
      (timeline, config) <- config.timelines.toSeq
      lb <- config.externalElb
      instanceState = Task.fork(
        (cache andThen ElasticLoadBalancing.describeInstanceHealth(lb.name))
          .run(config.ionrollerRoleArn)
      )(ionrollerExecutorService)
    } yield LiveStateCollector.recoverErrorsForTimeline(instanceState, timeline).map(timeline -> _)

    Task.gatherUnordered(tasks).map(_.toMap)
  }

  def mergeEnvironmentAndDNS(env: CheckedBy[TimelineName, List[ContainerEnvironment]], elb: CheckedBy[TimelineName, Seq[InstanceState]], dns: CheckedBy[TimelineName, Seq[String]]): LiveSystemState = {
    val now = DateTime.now

    val map = for {
      (k, v) <- env
      instanceStates = elb.get(k).sequence
      dnsDest = dns.get(k).getOrElse(Seq.empty.right)
      timelineState = {
        Apply[Result]
          .apply3(v, instanceStates, dnsDest)(LiveTimelineState(now, _, _, _))
          .leftMap(BrokenTimelineState(now, _))
          .merge[TimelineState]
      }
    } yield k -> timelineState

    LiveSystemState(map.toMap)
  }

  /**
   * Returns a Task which can provide the current live state of the system
   *
   * @param config The system configuration
   * @return A Task returning the current live state of the system
   */
  def liveSystemState(config: SystemConfiguration): Task[LiveSystemState] = {
    Nondeterminism[Task].nmap3(
      Task.fork(liveEnvironmentState(config))(ionrollerExecutorService),
      Task.fork(liveElbState(config))(ionrollerExecutorService),
      Task.fork(liveDNSState(config))(ionrollerExecutorService)
    )(mergeEnvironmentAndDNS)
  }
}

object LiveStateCollector extends StrictLogging {

  type Result[T] = NonEmptyList[Event] \/ T
  type CheckedBy[T, U] = Map[T, Result[U]]
  type UncheckedBy[T, U] = Map[T, U]
  type AWSCall[T] = Kleisli[Task, AWSClientCache, T]

  def apply(cache: Kleisli[Task, String, AWSClientCache]): LiveStateCollector = new LiveStateCollector(cache)

  /**
   * This method can be used to restore desired state of running services.
   * @param liveState
   * @param desiredState
   * @param systemConfig
   * @return
   */
  def synchronizedDesiredState(liveState: LiveSystemState, desiredState: DesiredSystemState, systemConfig: SystemConfiguration): DesiredSystemState = {
    val missingTimelines: Map[TimelineName, DesiredTimelineState] = for {
      (timelineName, timelineState: TimelineState) <- liveState.timelines
      conf <- systemConfig.timelines.get(timelineName)
      newDesiredState: DesiredTimelineState <- {
        desiredState.timelines.get(timelineName) match {
          case None => desiredStateFromLiveState(timelineName, timelineState, conf)
          case Some(t) => none
        }
      }
    } yield (timelineName, newDesiredState)
    desiredState.copy(timelines = desiredState.timelines ++ missingTimelines)
  }

  def getConfig(serviceName: String, timestamp: DateTime) = {
    for {
      table <- Dynamo.configTable(None)
      config <- Dynamo.getConfig(table, serviceName, timestamp)
    } yield config
  }

  def desiredStateFromLiveState(timelineName: TimelineName, timelineState: TimelineState, conf: TimelineConfiguration) = {
    timelineState match {
      case l: LiveTimelineState => {
        val targets = l.route53Targets.map(_.toLowerCase.dropRight(1)) // this  removes "." at the end
        val env = l.environments.collectFirst { case e if targets.contains(e.environmentDescription.getEndpointURL.toLowerCase) => e }
        env.map(e => {
          val tconf: TimelineConfiguration = getConfig(timelineName.name, e.setup.config.timestamp).attemptRun match {
            case \/-(s) => s.getOrElse(conf)
            case -\/(f) => {
              logger.error(f.getMessage, f)
              conf
            }
          }
          val setup = e.setup.copy(
            config = RuntimeConfiguration(tconf.ionrollerRoleArn, tconf.portMappings, tconf.runArgs, tconf.volumeMappings, tconf.timestamp),
            ebsConfiguration = tconf.ebsConfig
          )
          DesiredTimelineState(Seq.empty, Some((setup, None)))
        })
      }
      case b: BrokenTimelineState => none
    }
  }

  def recoverErrorsForTimeline[T](call: Task[T], timeline: TimelineName): Task[Result[T]] = {

    val exceptionHandlers: PartialFunction[Throwable, Task[NonEmptyList[Event]]] = {
      case awsSrvcEx: AmazonServiceException =>
        Task.delay(LiveStateCollector.exceptionHandler(timeline, awsSrvcEx.getErrorMessage, awsSrvcEx))
      case awsCliEx: AmazonClientException =>
        Task.delay(LiveStateCollector.exceptionHandler(timeline, awsCliEx.getMessage, awsCliEx))
    }

    call.map(_.right).handleWith(exceptionHandlers.andThen(_.map(_.left)))
  }

  def recoverErrorsForTimelines[T](call: Task[UncheckedBy[TimelineName, T]], names: Seq[TimelineName]): Task[CheckedBy[TimelineName, T]] = {
    def exceptionHandlers(timelines: Seq[TimelineName]): PartialFunction[Throwable, Task[Map[TimelineName, NonEmptyList[Event]]]] = {
      case awsSrvcEx: AmazonServiceException => {
        Task.delay(timelines.map(t => {
          t -> LiveStateCollector.exceptionHandler(t, awsSrvcEx.getErrorMessage, awsSrvcEx)
        }).toMap)
      }
      case awsCliEx: AmazonClientException => {
        Task.delay(timelines.map(t => {
          t -> LiveStateCollector.exceptionHandler(t, awsCliEx.getMessage, awsCliEx)
        }).toMap)
      }
    }

    call.map(_.map(kv => kv._1 -> kv._2.right)).handleWith(exceptionHandlers(names).andThen(_.map(_.map(kv => kv._1 -> kv._2.left))))
  }

  def exceptionHandler(timeline: TimelineName, message: String, ex: Exception) =
    NonEmptyList(Event(
      ExceptionEvent,
      timeline,
      None,
      s"[ERROR] ${message}",
      ex.some
    ))
}
