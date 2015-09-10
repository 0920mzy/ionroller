package ionroller

import com.typesafe.scalalogging.StrictLogging
import ionroller.aws.AWSClientCache
import ionroller.deployment.eb.ElasticBeanstalkImpl
import ionroller.stream._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scalaz.Kleisli
import scalaz.concurrent.Task
import scalaz.stream._
import scalaz.stream.async.mutable.Signal
import scalaz.stream.async.mutable.Signal.CompareAndSet
import scalaz.syntax.std.option._

class Pipeline(clientCache: Kleisli[Task, String, AWSClientCache], desiredStateSink: Sink[Task, (TimelineName, DesiredTimelineState)], systemData: Process[Task, SystemData]) extends StrictLogging {
  // This will contain a cached version of the live state (especially for use with the API).
  val liveStateSignal: Signal[Option[LiveSystemState]] = async.signalOf(None)

  val saveLiveStateDiffs: Process[Task, Unit] =
    liveStateSignal.discrete
      .pipe(process1.stripNone)
      .logDebug(logger, _ => "New live state set, processing diffs")
      .pipe(emitChanges(LiveSystemState.diffTimelineState))
      .zipWithIndex
      .map { case (evt, idx) => evt.copy(timestamp = evt.timestamp + idx % 1000) }
      .through(Process.constant(logEvent(_)))

  private[ionroller] def getLiveInfoForSetup(setup: EBSSetup, s: LiveTimelineState) = {
    val liveEnv = s.environments.find(env => EBSSetup.sameEnvironment(env.setup, setup))
    val instanceData = for {
      env <- liveEnv
      asg <- env.asg
      elbInstanceStates <- s.externalElbInstanceStates
      asgInstances = asg.getInstances.asScala
    } yield asgInstances.map(i => (i, elbInstanceStates.find(is => is.getInstanceId == i.getInstanceId)))

    instanceData match {
      case None => LiveInfoForSetup(liveEnv, Seq.empty, Seq.empty)
      case Some(instances) =>
        val registered = instances collect {
          case (i, Some(is)) => (i, is)
        }

        val unregistered = instances collect {
          case (i, None) => i
        }

        LiveInfoForSetup(liveEnv, registered, unregistered)
    }
  }

  private[ionroller] def splitByPipeline(signalState: SystemData): Process0[TimelineData] = {
    val timelines = for {
      (pipeline, config) <- signalState.config.timelines.toSeq
      liveState = signalState.current.timelines.getOrElse(pipeline, LiveTimelineState.empty)
      desiredState <- signalState.desired.timelines.get(pipeline)
      timelines <- liveState match {
        case s: LiveTimelineState =>

          val curEnvironment = for {
            setup <- desiredState.curEnvironment
            liveData = getLiveInfoForSetup(setup, s)
          } yield (setup, liveData)

          val nextEnvironment = for {
            setup <- desiredState.nextEnvironment
            liveData = getLiveInfoForSetup(setup, s)
          } yield (setup, liveData)

          TimelineData(pipeline, signalState.config, config, desiredState, s, curEnvironment, nextEnvironment, enabled(pipeline)).some

        case t => None
      }
    } yield timelines

    Process.emitAll(timelines)
  }

  def actionProcess(strategy: DeploymentStrategy): Process[Task, (TimelineData, ActionResult)] =
    systemData
      .observe(liveStateSignal.sink.contramap({ s: SystemData => async.mutable.Signal.Set(s.current.some) }))
      .flatMap(splitByPipeline)
      .logDebug(logger, timelineData => s"Processing timeline ${timelineData.name.name}")
      //.logDebug(logger, timelineData => s"Data on Current: ${timelineData.curEnvironment}")
      //.logDebug(logger, timelineData => s"Data on Next: ${timelineData.nextEnvironment}")
      .map(actionFromStrategy(strategy, _))
      .pipe(process1.stripNone)
      .logInfo(logger, action => s"Decided on action ${action._2} for timeline ${action._1.name.name} (enabled=${action._1.enabled})")

  def server(strategy: DeploymentStrategy): Process[Task, Unit] = {
    val serverLoop =
      actionProcess(strategy)
        .filter(_._1.enabled)
        .through(ElasticBeanstalkImpl.actionToTask(clientCache, desiredStateSink))
        .logThrowable(logger, "Exception when performing action")
        .pipe(process1.stripNone)
        .zipWithIndex
        .map { case (evt, idx) => evt.copy(timestamp = evt.timestamp + idx % 1000) }
        .through(Process.constant(logEvent(_)))

    (Process.eval_(Task.delay(logger.debug("Start of server loop"))) ++
      serverLoop ++
      Process.eval_(Task.delay(logger.debug("End of server loop"))) ++
      time.sleep(10.seconds)).repeat
  }

  private[ionroller] def actionFromStrategy(strategy: DeploymentStrategy, timeline: TimelineData): Option[(TimelineData, ActionResult)] = {
    val result = strategy.deploymentCalls(desiredStateSink).foldLeft[ActionResultOrNext](TryNextAction)((s, f) =>
      s match {
        case TryNextAction => f(timeline)
        case other => other
      })

    result match {
      case TryNextAction => None
      case other: ActionResult => Some((timeline, other))
    }
  }
}

object Pipeline extends StrictLogging {
  def apply(clientCache: Kleisli[Task, String, AWSClientCache], desiredStateSink: Sink[Task, (TimelineName, DesiredTimelineState)], liveState: LiveSystemState, configurationSignal: Signal[SystemConfiguration], desiredStateSignal: Signal[DesiredSystemState], systemData: SystemData): Pipeline = {
    new Pipeline(clientCache, desiredStateSink, Process.emit(systemData))
  }

  def apply(clientCache: Kleisli[Task, String, AWSClientCache], configurationSignal: Signal[SystemConfiguration], desiredStateSignal: Signal[DesiredSystemState], liveState: SystemConfiguration => Process[Task, LiveSystemState]) = {

    val systemData: Process[Task, SystemData] = for {
      config <- Process.eval(configurationSignal.get)
      liveState <- liveState(config) //TODO update signal here
      desiredState <- Process.eval(desiredStateSignal.get).observe(desiredStateSignal.sink.contramap({ s: DesiredSystemState =>
        async.mutable.Signal.Set(s /*LiveStateCollector.synchronizedDesiredState(liveState, s, config)*/ ) // Uncomment to restore desired state
      }))
    } yield SystemData(config, desiredState, liveState)

    val desiredStateSink: Sink[Task, (TimelineName, DesiredTimelineState)] = {
      desiredStateSignal.sink.contramap { i: (TimelineName, DesiredTimelineState) =>
        def updateTimeline(s: Option[DesiredSystemState]): Option[DesiredSystemState] = {
          s match {
            case None => Some(DesiredSystemState(Map(i)))
            case Some(d) => d.copy(timelines = d.timelines.updated(i._1, i._2)).some
          }
        }

        CompareAndSet(updateTimeline)
      }
    }

    new Pipeline(clientCache, desiredStateSink, systemData)
  }
}
