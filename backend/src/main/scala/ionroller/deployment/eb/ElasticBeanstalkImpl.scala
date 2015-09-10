package ionroller.deployment.eb

import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.elasticbeanstalk.model.UpdateEnvironmentResult
import com.amazonaws.services.route53.model.ChangeInfo
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import ionroller.aws._
import ionroller.tracking._
import ionroller.{WaitingForNextRolloutStep => _, WaitingForTrafficDecrement => _, WaitingForTrafficIncrement => _, _}
import org.joda.time.DateTime

import scala.concurrent.duration._
import scalaz.Scalaz._
import scalaz._
import scalaz.concurrent.Task
import scalaz.stream.{Sink, _}

final case class EnvAndEvent(env: Option[EBSSetup], event: Task[Option[Event]])

object ElasticBeanstalkImpl {

  private[eb] def updateEnvToUsedOrUnused(timeline: TimelineData, env: ContainerEnvironment, inUse: Boolean): Kleisli[Task, AWSClientCache, UpdateEnvironmentResult] = {
    val dateVar =
      if (inUse)
        none
      else
        new DateTime().some

    val desc = DescriptionData(env.timeline, env.setup.dockerImage, env.setup.config.timestamp, dateVar, env.elbChanged, env.rolloutStatus)

    for {
      updateEnv <- ElasticBeanstalk.updateEnvironmentDescription(env.environmentDescription.getEnvironmentName, desc.toDescription)
    } yield updateEnv
  }

  def createEnvironmentAction(timeline: TimelineData, envDetails: EBSSetup): Kleisli[Task, AWSClientCache, DeploymentResult] = {
    for {
      deployment <- EBSDeployer(timeline.name, timeline.timelineConfig.url, timeline.timelineConfig.externalElb, envDetails).deploy
    } yield deployment
  }

  def removeEnvironmentAction(timeline: TimelineData, envDetails: ContainerEnvironment): Kleisli[Task, AWSClientCache, Unit] = {
    for {
      deleteEnv <- ElasticBeanstalk.terminateEnvironment(envDetails.environmentDescription.getEnvironmentName)
      deleteVer <- ElasticBeanstalk.deleteApplicationVersion(envDetails.environmentDescription.getApplicationName, envDetails.environmentDescription.getVersionLabel)
    } yield ()
  }

  def updateRemovalRequestsAction(timeline: TimelineData, removals: Seq[EnvironmentToRemove], desiredStateSink: Sink[Task, (TimelineName, DesiredTimelineState)]): Task[Unit] = {
    val nextState = timeline.desired.copy(environmentsToRemove = removals)
    Process.emit((timeline.name, nextState)).toSource.to(desiredStateSink).run
  }

  def moveDNSAction(timeline: TimelineData, env: ContainerEnvironment): Task[Option[ChangeInfo]] = {
    for {
      roleCache <- AWSClientCache.getCache(timeline.timelineConfig.ionrollerRoleArn)
      envCache <- AWSClientCache.getCache(env.setup.config.setupRole)
      r53Change <- {
        env.curEndpoint match {
          case None =>
            Route53.setAliasRecordForEnv(roleCache, envCache, timeline.timelineConfig.url, timeline.timelineConfig.hostedZoneId, env.created.id)
          case Some(\/-(dns)) =>
            Route53.setAliasRecordForEnv(roleCache, envCache, timeline.timelineConfig.url, timeline.timelineConfig.hostedZoneId, env.created.id)
          case Some(-\/(ip)) =>
            Route53.setARecordForIP(timeline.timelineConfig.url, timeline.timelineConfig.hostedZoneId, ip).run(roleCache).map(_.some)
        }
      }
    } yield r53Change
  }

  def updateEnvWithRolloutStatus(timeline: TimelineData, env: ContainerEnvironment, rolloutStatus: RolloutStatus): Kleisli[Task, AWSClientCache, UpdateEnvironmentResult] = {
    val desc = DescriptionData(env.timeline, env.setup.dockerImage, env.setup.config.timestamp, env.unused.map(_.at), env.elbChanged, rolloutStatus)

    for {
      updateEnv <- ElasticBeanstalk.updateEnvironmentDescription(env.environmentDescription.getEnvironmentName, desc.toDescription)
    } yield updateEnv
  }

  def updateEnvToElbHealthCheckChanged(timeline: TimelineData, env: ContainerEnvironment): Kleisli[Task, AWSClientCache, UpdateEnvironmentResult] = {
    val desc = DescriptionData(env.timeline, env.setup.dockerImage, env.setup.config.timestamp, env.unused.map(_.at), true.some, env.rolloutStatus)

    for {
      updateEnv <- ElasticBeanstalk.updateEnvironmentDescription(env.environmentDescription.getEnvironmentName, desc.toDescription)
    } yield updateEnv
  }

  def updateElbHealthCheckAction(timeline: TimelineData, env: ContainerEnvironment): Kleisli[Task, AWSClientCache, UpdateEnvironmentResult] = {
    Kleisli { cache =>
      for {
        asgs <- ElasticBeanstalk.getAutoScalingGroups(env.environmentDescription.getEnvironmentId).run(cache)
        _ <- AutoScaling.updateElbHealthCheck(asgs, "ELB", 10.minutes).run(cache)
        update <- updateEnvToElbHealthCheckChanged(timeline, env).run(cache)
      } yield update
    }
  }

  def startNextDeploymentAction(timeline: TimelineData, desiredStateSignal: Sink[Task, (TimelineName, DesiredTimelineState)]): Task[Unit] = {
    Process.emit((timeline.name, timeline.desired.startNextDeployment)).toSource.to(desiredStateSignal).run
  }

  def exceptionHandlers(envSetup: Option[EBSSetup], timeline: TimelineData): PartialFunction[Throwable, Task[Event]] = {
    case awsSrvcEx: AmazonServiceException =>
      Task.delay(Event(
        ExceptionEvent,
        timeline.name,
        envSetup.map(_.dockerImage.tag),
        s"[ERROR] ${awsSrvcEx.getErrorMessage}",
        awsSrvcEx.some
      ))
    case awsCliEx: AmazonClientException =>
      Task.delay(Event(
        ExceptionEvent,
        timeline.name,
        envSetup.map(_.dockerImage.tag),
        s"[ERROR] ${awsCliEx.getMessage}",
        awsCliEx.some
      ))
  }

  def handleWaitingForEnvironment(timeline: TimelineData, envDetails: EBSSetup): Task[Option[Event]] = {
    Task.now(Event(EnvironmentNotHealthy, timeline.name, envDetails.dockerImage.tag.some, "[INFO] Waiting for environment to become healthy.", envDetails.some).some)
  }

  def handleIncrementTraffic(timeline: TimelineData, envDetails: ContainerEnvironment, instanceIds: Seq[String], to: Option[Int]): Kleisli[Task, AWSClientCache, Option[Event]] = {
    (timeline.timelineConfig.externalElb.map(_.name), envDetails.asg) match {
      case (Some(elb), Some(asg)) =>
        for {
          incrementTraffic <- ElasticLoadBalancing.registerInstances(elb, instanceIds)
        } yield Event(IncrementTrafficRequested, timeline.name, envDetails.setup.dockerImage.tag.some, s"[INFO] Incrementing traffic to ${to.getOrElse("all")} instances.", envDetails.setup.some).some

      case _ => Kleisli { cache => Task.now(none) }
    }
  }

  def handleDecrementTraffic(timeline: TimelineData, envDetails: ContainerEnvironment, instanceIds: Seq[String], to: Option[Int]): Kleisli[Task, AWSClientCache, Option[Event]] = {
    (timeline.timelineConfig.externalElb.map(_.name), envDetails.asg) match {
      case (Some(elb), Some(asg)) =>
        for {
          decrementTraffic <- ElasticLoadBalancing.deregisterInstances(elb, instanceIds)
        } yield Event(DecrementTrafficRequested, timeline.name, envDetails.setup.dockerImage.tag.some, s"[INFO] Decrementing traffic.", envDetails.setup.some).some

      case _ => Kleisli { cache => Task.now(none) }
    }
  }

  def handleWaitingForTrafficDecrement(timeline: TimelineData, envDetails: ContainerEnvironment): Task[Option[Event]] = {
    Task.now(Event(tracking.WaitingForTrafficDecrement, timeline.name, envDetails.setup.dockerImage.tag.some, "[INFO] Waiting for traffic decrement.", envDetails.some).some)
  }

  def handleWaitingForTrafficIncrement(timeline: TimelineData, envDetails: ContainerEnvironment): Task[Option[Event]] = {
    Task.now(Event(tracking.WaitingForTrafficIncrement, timeline.name, envDetails.setup.dockerImage.tag.some, "[INFO] Waiting for traffic increment.", envDetails.some).some)
  }

  def handleWaitingForNextRolloutStep(timeline: TimelineData, envDetails: ContainerEnvironment, at: DateTime): Task[Option[Event]] = {
    Task.now(Event(tracking.WaitingForNextRolloutStep, timeline.name, envDetails.setup.dockerImage.tag.some, s"[INFO] Waiting for next traffic move at ${at}", envDetails.some).some)
  }

  def handleStartRolloutStep(timeline: TimelineData, envDetails: ContainerEnvironment, step: Int): Kleisli[Task, AWSClientCache, Option[Event]] = {
    updateEnvWithRolloutStatus(timeline, envDetails, RolloutStep(step)) map { _ =>
      None
    }
  }

  def handleFinishRolloutStep(timeline: TimelineData, envDetails: ContainerEnvironment, step: Int, at: DateTime): Kleisli[Task, AWSClientCache, Option[Event]] = {
    updateEnvWithRolloutStatus(timeline, envDetails, RolloutStepComplete(step, at)) map { _ =>
      None
    }
  }

  def handleFinishRollout(timeline: TimelineData, envDetails: ContainerEnvironment, at: DateTime): Kleisli[Task, AWSClientCache, Option[Event]] = {
    updateEnvWithRolloutStatus(timeline, envDetails, RolloutComplete(at)) map { _ =>
      Event(FinishingTrafficRollout, timeline.name, none, s"[INFO] Finishing rollout.", none).some
    }
  }

  def handleCreateEnvironment(timeline: TimelineData, envDetails: EBSSetup): Kleisli[Task, AWSClientCache, Option[Event]] = {
    createEnvironmentAction(timeline, envDetails) map { deployment =>
      Event(DeploymentStarted, timeline.name, envDetails.dockerImage.tag.some, s"[INFO] Deployment started.", deployment.some).some
    }
  }

  def handleRemoveEnvironment(timeline: TimelineData, envDetails: ContainerEnvironment): Kleisli[Task, AWSClientCache, Option[Event]] = {
    removeEnvironmentAction(timeline, envDetails) map { _ =>
      Event(EnvironmentRemovalRequested, timeline.name, envDetails.setup.dockerImage.tag.some, "[INFO] User requested environment removal.", envDetails.some).some
    }
  }

  def handleUpdateRemovalRequests(timeline: TimelineData, removals: Seq[EnvironmentToRemove], desiredStateSink: Sink[Task, (TimelineName, DesiredTimelineState)]): Task[Option[Event]] = {
    updateRemovalRequestsAction(timeline, removals, desiredStateSink) map { _ =>
      // TODO: be more granular as to which requests were removed, why (not unused, missing, etc.)
      Event(RemovalListUpdated, timeline.name, none, s"[INFO] Updated environments to be removed: ${removals.mkString(", ")}", none).some
    }
  }

  def handleMarkEnvironmentUnused(timeline: TimelineData, envDetails: ContainerEnvironment): Kleisli[Task, AWSClientCache, Option[Event]] = {
    updateEnvToUsedOrUnused(timeline, envDetails, false) map { update =>
      Event(EnvironmentMarkedUnused, timeline.name, envDetails.setup.dockerImage.tag.some, "[INFO] Marked environment as unused.", update.some).some
    }
  }

  def handleMarkEnvironmentUsed(timeline: TimelineData, envDetails: ContainerEnvironment): Kleisli[Task, AWSClientCache, Option[Event]] = {
    updateEnvToUsedOrUnused(timeline, envDetails, true) map { update =>
      Event(EnvironmentMarkedUsed, timeline.name, envDetails.setup.dockerImage.tag.some, "[INFO] Marked environment as used.", update.some).some
    }
  }

  def handleUpdateElbHealthCheck(timeline: TimelineData, envDetails: ContainerEnvironment): Kleisli[Task, AWSClientCache, Option[Event]] = {
    if (!envDetails.elbChanged.getOrElse(true)) {
      updateElbHealthCheckAction(timeline, envDetails) map { update =>
        Event(ElbHealthCheckAdded, timeline.name, envDetails.setup.dockerImage.tag.some, "[INFO] ELB Health Check added.", none).some
      }
    } else {
      Kleisli(_ => Task.now(None))
    }
  }

  def handleMoveDNS(timeline: TimelineData, envDetails: ContainerEnvironment): Task[Option[Event]] = {
    moveDNSAction(timeline, envDetails) map {
      case Some(changeInfo) =>
        Event(TrafficMoved, timeline.name, envDetails.setup.dockerImage.tag.some, s"""[INFO] Traffic moved to: ${envDetails.curEndpoint.fold("")(_.merge)}""", envDetails.environmentDescription.some).some
      case none =>
        None // TODO: figure out what happens here...
    }
  }

  def handleStartNextDeployment(timeline: TimelineData, desiredStateSink: Sink[Task, (TimelineName, DesiredTimelineState)]): Task[Option[Event]] = {
    startNextDeploymentAction(timeline, desiredStateSink) map { _ =>
      for {
        (curImage, currConf) <- timeline.desired.curEnvironment.map(c => (c.dockerImage, c.config.timestamp))
        (nextImage, nextConf) <- timeline.desired.nextEnvironment.map(c => (c.dockerImage, c.config.timestamp))
        if curImage == nextImage && currConf == nextConf
      } yield Event(ReleaseRequestIgnored, timeline.name, curImage.tag.some, s"[INFO] Ignoring release command as version ${curImage.tag} is the current version.", timeline.some)
    }
  }

  def handleAttachElb(timeline: TimelineData, envDetails: ContainerEnvironment, asg: AutoScalingGroup, lb: String): Kleisli[Task, AWSClientCache, Option[Event]] = {
    AutoScaling.attachElb(asg, lb) map { _ =>
      Event(AttachedELB, timeline.name, envDetails.setup.dockerImage.tag.some, s"""[INFO] Attached environment to external load balancer""", envDetails.environmentDescription.some).some
    }
  }

  def handleDetachElb(timeline: TimelineData, envDetails: ContainerEnvironment, asg: AutoScalingGroup, lb: String): Kleisli[Task, AWSClientCache, Option[Event]] = {
    AutoScaling.detachElb(asg, lb) map { _ =>
      Event(DetachedELB, timeline.name, envDetails.setup.dockerImage.tag.some, s"""[INFO] Detached environment from external load balancer""", envDetails.environmentDescription.some).some
    }
  }

  def actionToTask(cache: Kleisli[Task, String, AWSClientCache], desiredStateSink: Sink[Task, (TimelineName, DesiredTimelineState)]): Channel[Task, (TimelineData, ActionResult), Throwable \/ Option[Event]] = Process.constant {
    case (timeline, r) =>
      val envAndEvent = r match {

        case WaitingForEnvironment(envDetails) =>
          EnvAndEvent(envDetails.some, handleWaitingForEnvironment(timeline, envDetails))

        case CreateEnvironment(envDetails) =>
          EnvAndEvent(
            envDetails.some,
            (cache andThen handleCreateEnvironment(timeline, envDetails))
              .run(timeline.timelineConfig.ionrollerRoleArn)
          )

        case RemoveEnvironment(envDetails) =>
          EnvAndEvent(
            envDetails.setup.some,
            (cache andThen handleRemoveEnvironment(timeline, envDetails))
              .run(envDetails.setup.config.setupRole)
          )

        case UpdateRemovalRequests(removals) =>
          EnvAndEvent(
            none,
            handleUpdateRemovalRequests(timeline, removals, desiredStateSink)
          )

        case MarkEnvironmentUnused(envDetails) =>
          EnvAndEvent(
            envDetails.setup.some,
            (cache andThen handleMarkEnvironmentUnused(timeline, envDetails))
              .run(envDetails.setup.config.setupRole)
          )

        case MarkEnvironmentUsed(envDetails) =>
          EnvAndEvent(
            envDetails.setup.some,
            (cache andThen handleMarkEnvironmentUsed(timeline, envDetails))
              .run(envDetails.setup.config.setupRole)
          )

        case UpdateElbHealthCheck(envDetails) =>
          EnvAndEvent(
            envDetails.setup.some,
            (cache andThen handleUpdateElbHealthCheck(timeline, envDetails))
              .run(envDetails.setup.config.setupRole)
          )

        case MoveDNS(envDetails) =>
          EnvAndEvent(
            envDetails.setup.some,
            handleMoveDNS(timeline, envDetails)
          )

        case StartNextDeployment =>
          EnvAndEvent(
            none,
            handleStartNextDeployment(timeline, desiredStateSink)
          )

        case IncrementTraffic(envDetails, instanceIds, to) =>
          EnvAndEvent(
            envDetails.setup.some,
            (cache andThen handleIncrementTraffic(timeline, envDetails, instanceIds, to))
              .run(envDetails.setup.config.setupRole)
          )

        case DecrementTraffic(envDetails, instanceIds, to) =>
          EnvAndEvent(
            envDetails.setup.some,
            (cache andThen handleDecrementTraffic(timeline, envDetails, instanceIds, to))
              .run(envDetails.setup.config.setupRole)
          )

        case ionroller.WaitingForTrafficDecrement(envDetails) =>
          EnvAndEvent(
            envDetails.setup.some,
            handleWaitingForTrafficDecrement(timeline, envDetails)
          )

        case ionroller.WaitingForTrafficIncrement(envDetails) =>
          EnvAndEvent(
            envDetails.setup.some,
            handleWaitingForTrafficIncrement(timeline, envDetails)
          )

        case StartRolloutStep(envDetails, step) =>
          EnvAndEvent(
            none,
            (cache andThen handleStartRolloutStep(timeline, envDetails, step))
              .run(envDetails.setup.config.setupRole)
          )

        case FinishRolloutStep(envDetails, step, at) =>
          EnvAndEvent(
            none,
            (cache andThen handleFinishRolloutStep(timeline, envDetails, step, at))
              .run(envDetails.setup.config.setupRole)
          )

        case ionroller.WaitingForNextRolloutStep(envDetails, at) =>
          EnvAndEvent(
            none,
            handleWaitingForNextRolloutStep(timeline, envDetails, at)
          )

        case FinishRollout(envDetails, at) =>
          EnvAndEvent(
            none,
            (cache andThen handleFinishRollout(timeline, envDetails, at))
              .run(envDetails.setup.config.setupRole)
          )

        case AttachElb(envDetails, asg, lb) =>
          EnvAndEvent(
            none,
            (cache andThen handleAttachElb(timeline, envDetails, asg, lb))
              .run(timeline.timelineConfig.ionrollerRoleArn)
          )

        case DetachElb(envDetails, asg, lb) =>
          EnvAndEvent(
            none,
            (cache andThen handleDetachElb(timeline, envDetails, asg, lb))
              .run(timeline.timelineConfig.ionrollerRoleArn)
          )

      }

      envAndEvent.event.handleWith(exceptionHandlers(envAndEvent.env, timeline).andThen(_.map(_.some))).attempt
  }
}
