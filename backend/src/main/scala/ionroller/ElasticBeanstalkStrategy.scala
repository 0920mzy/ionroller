package ionroller

import com.amazonaws.services.elasticloadbalancing.model.InstanceState
import ionroller.aws.AutoScaling
import play.api.libs.json.JsObject

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scalaz.\/
import scalaz.concurrent.Task
import scalaz.stream.Sink
import scalaz.syntax.either._

object ElasticBeanstalkStrategy extends DeploymentStrategy {

  val targetEnvironment: TimelineData => Option[(EBSSetup, LiveInfoForSetup)] = {
    timeline =>
      timeline.nextEnvironment orElse timeline.curEnvironment
  }

  // This checks if any instances from the container's AutoScaling group are present in the
  // set of registered instances on the external ELB. If they are, then the environment is
  // still in use, and should not be deleted.
  def instancesInElb(env: ContainerEnvironment, externalElb: Seq[InstanceState]): Set[String] = {
    env.asg match {
      case None => Set.empty
      case Some(asg) =>
        val asgInstances = asg.getInstances.asScala.map(_.getInstanceId).toSet
        val externalElbInstances = externalElb.map(_.getInstanceId).toSet

        asgInstances intersect externalElbInstances
    }
  }

  val unusedAndUsedEnvironments: TimelineData => (Seq[ContainerEnvironment], Seq[ContainerEnvironment]) = {
    timeline =>
      timeline.liveState.environments.partition({ env =>
        !(timeline.curEnvironment.flatMap(_._2.env) ++ timeline.nextEnvironment.flatMap(_._2.env)).toSeq.contains(env) &&
          instancesInElb(env, timeline.liveState.externalElbInstanceStates.getOrElse(Seq.empty)).isEmpty
      })
  }

  val unusedEnvironments: TimelineData => Seq[ContainerEnvironment] = {
    timeline =>
      unusedAndUsedEnvironments(timeline)._1
  }

  val usedEnvironments: TimelineData => Seq[ContainerEnvironment] = {
    timeline =>
      unusedAndUsedEnvironments(timeline)._2
  }

  val createEnvironment: TimelineData => ActionResultOrNext = {
    timeline =>
      (timeline.nextEnvironment, timeline.curEnvironment) match {
        case (Some((setup, LiveInfoForSetup(None, _, _))), _) => CreateEnvironment(setup)
        case (_, Some((setup, LiveInfoForSetup(None, _, _)))) => CreateEnvironment(setup)
        case _ => TryNextAction
      }
  }

  val updateElbHealthCheck: TimelineData => ActionResultOrNext = {
    timeline =>
      {
        val runtimeEnv = {

          def hasASGConfig(resources: JsObject): Boolean = {
            resources.fields.exists(r => (r._2 \ "Type").asOpt[String] == Some("AWS::AutoScaling::AutoScalingGroup"))
          }

          for {
            (setup, liveInfo) <- targetEnvironment(timeline)
            liveEnv <- liveInfo.env
            if liveEnv.elbChanged == Some(false)
            if !liveEnv.setup.ebsConfiguration.resources.fold(false)(hasASGConfig)
          } yield liveEnv
        }

        runtimeEnv match {
          case Some(envDetails) => UpdateElbHealthCheck(envDetails)
          case None => TryNextAction
        }
      }
  }

  val moveARecord: TimelineData => ActionResultOrNext = {
    timeline =>

      // Ugly code; Right == DNS name, Left == IP address
      def curEndpoint(e: ContainerEnvironment): Option[String \/ String] = {
        val beforeLowerCase = for {
          desc <- Option(e.environmentDescription.getEndpointURL)
        } yield desc

        val lowerCase = beforeLowerCase.map(_.toLowerCase)

        if (beforeLowerCase == lowerCase) {
          lowerCase.map(_.left)
        } else {
          lowerCase.map(_ + ".").map(_.right)
        }
      }

      val curRoute53 = timeline.liveState.route53Targets.map(_.toLowerCase)

      def aRecordCorrect(e: ContainerEnvironment): Boolean = curRoute53.contains(curEndpoint(e).map(_.fold(identity, identity)).getOrElse(""))

      targetEnvironment(timeline).flatMap(_._2.env) match {
        case None => TryNextAction
        case Some(e) if e.environmentDescription.getStatus != "Ready" => WaitingForEnvironment(e.setup)
        case Some(e) if !e.healthy => WaitingForEnvironment(e.setup)
        case Some(e) if aRecordCorrect(e) => TryNextAction
        case Some(e) => MoveDNS(e)
      }
  }

  val moveEnvironmentToUnusedOrUsed: TimelineData => ActionResultOrNext = {
    timeline =>
      {
        val (unused, used) = unusedAndUsedEnvironments(timeline)

        def isMarkedUnused(env: ContainerEnvironment): Boolean =
          env.unused.isDefined && env.environmentDescription.getStatus == "Ready"

        def isMarkedUsed(env: ContainerEnvironment): Boolean =
          !env.unused.isDefined && env.environmentDescription.getStatus == "Ready"

        val task = for {
          envAndInUse <- used.find(isMarkedUnused).map((_, true)) orElse unused.find(isMarkedUsed).map((_, false))
        } yield envAndInUse match {
          case (e, true) => MarkEnvironmentUsed(e)
          case (e, false) => MarkEnvironmentUnused(e)
        }

        task.getOrElse(TryNextAction)
      }
  }

  val startNextDeployment: TimelineData => ActionResultOrNext = {
    timeline =>
      {
        timeline.desired.nextEnvironment match {
          case Some(_) => StartNextDeployment
          case None => TryNextAction
        }
      }
  }

  val dropEnvironment: TimelineData => ActionResultOrNext = {
    timeline =>
      // We should filter out versions that do not exist
      val versionsWithEnvironments: Map[EnvironmentToRemove, ContainerEnvironment] = (timeline.desired.environmentsToRemove map {

        case env @ EnvironmentToRemove(version, config, force) =>
          val eligibleEnvs =
            if (force)
              timeline.liveState.environments
            else
              unusedEnvironments(timeline)
          env -> eligibleEnvs.find(_.setup.dockerImage.tag.tag == version)
      } collect {
        case (envToRem, Some(env)) =>
          envToRem -> env
      }).toMap

      val envToRemove = versionsWithEnvironments.find(_._2.environmentDescription.getStatus == "Ready").map(_._2)
      val newToRemove = versionsWithEnvironments.map(_._1).toSeq
      val nextState = timeline.desired.copy(environmentsToRemove = newToRemove)

      envToRemove match {
        case Some(env) =>
          RemoveEnvironment(env)
        case None if newToRemove != timeline.desired.environmentsToRemove && versionsWithEnvironments.isEmpty =>
          UpdateRemovalRequests(newToRemove)
        case None if newToRemove != timeline.desired.environmentsToRemove && versionsWithEnvironments.nonEmpty =>
          val ebs = versionsWithEnvironments.head._2.setup
          WaitingForEnvironment(ebs)
        case None =>
          TryNextAction
      }
  }

  val shutdownEnvironment: TimelineData => ActionResultOrNext = {
    timeline =>
      val now = timeline.liveState.at

      val removeUnusedTime = timeline.timelineConfig.removeUnusedAfter.getOrElse(1.hour)
      val jodaRemoveUnusedTime = org.joda.time.Duration.millis(removeUnusedTime.toMillis)

      val unusedTimeline =
        unusedEnvironments(timeline).find(env => env.unused.fold(false)(t => now.minus(jodaRemoveUnusedTime).isAfter(t.at)))

      unusedTimeline match {
        case None => TryNextAction
        case Some(env) => RemoveEnvironment(env)
      }
  }

  /*
   * There must be a next environment (otherwise we are not in a rollout situation).
   * There must be an unregistered instance for the next environment.
   * There must also be an external ELB configured.
   */
  val incrementTraffic: TimelineData => ActionResultOrNext = {
    timeline =>

      val maybeAction = for {
        nextEnv <- timeline.nextEnvironment.orElse(timeline.curEnvironment)
        elbSettings <- timeline.timelineConfig.externalElb
        container <- nextEnv._2.env
        registered = nextEnv._2.registeredInstances
        unregistered = nextEnv._2.unregisteredInstances
      } yield {
        container.rolloutStatus match {

          case RolloutStep(inc) if registered.size < inc && unregistered.nonEmpty =>
            IncrementTraffic(container, unregistered.take(inc - registered.size).map(_.getInstanceId), Some(inc))

          case RolloutStep(inc) if registered.map(_._2).count(_.getState == "InService") < inc && (!registered.map(_._2).exists(_.getState != "OutOfService") || unregistered.nonEmpty) =>
            WaitingForTrafficIncrement(container)

          case RolloutComplete(at) if unregistered.nonEmpty =>
            IncrementTraffic(container, unregistered.map(_.getInstanceId), None)

          case RolloutComplete(at) if registered.map(_._2).exists(_.getState == "OutOfService") =>
            WaitingForTrafficIncrement(container)

          case _ => TryNextAction
        }
      }

      maybeAction.getOrElse(TryNextAction)
  }

  /*
   * There must be a next environment (otherwise we are not in a rollout situation).
   * There must be a current environment (should be true if there's a next one!).
   * There must be a registered instance for the current environment.
   * There must also be an external ELB configured.
   */
  val decrementTraffic: TimelineData => ActionResultOrNext = {
    timeline =>

      val maybeAction = for {
        nextEnv <- timeline.nextEnvironment
        curEnv <- timeline.curEnvironment
        elbSettings <- timeline.timelineConfig.externalElb
        nextContainer <- nextEnv._2.env
        curContainer <- curEnv._2.env
        registered = curEnv._2.registeredInstances
        unregistered = curEnv._2.unregisteredInstances
      } yield {
        nextContainer.rolloutStatus match {

          case RolloutStep(inc) if unregistered.size < inc && registered.nonEmpty && !registered.map(_._2).exists(i => i.getState == "OutOfService" && i.getDescription == "Instance is not currently registered with the LoadBalancer.") =>
            DecrementTraffic(curContainer, registered.take(inc - unregistered.size).map(_._2.getInstanceId), Some(inc))

          case RolloutStep(inc) if unregistered.size < inc && registered.nonEmpty =>
            WaitingForTrafficDecrement(curContainer)

          case RolloutComplete(at) if registered.nonEmpty && !registered.map(_._2).exists(i => i.getState == "OutOfService" && i.getDescription == "Instance is not currently registered with the LoadBalancer.") =>
            DecrementTraffic(curContainer, registered.map(_._2.getInstanceId), None)

          case RolloutComplete(at) if registered.nonEmpty =>
            WaitingForTrafficDecrement(curContainer)

          case _ => TryNextAction
        }
      }

      maybeAction.getOrElse(TryNextAction)
  }

  /*
   * There must be a next environment (otherwise we are not in a rollout situation).
   * There must also be an external ELB configured.
   */
  val updateRolloutState: TimelineData => ActionResultOrNext = {
    timeline =>
      val maybeAction = for {
        nextEnv <- timeline.nextEnvironment
        elbSettings <- timeline.timelineConfig.externalElb
        container <- nextEnv._2.env
        registered = nextEnv._2.registeredInstances
        unregistered = nextEnv._2.unregisteredInstances
      } yield {
        container.rolloutStatus match {

          case RolloutStep(inc) if inc >= registered.size + unregistered.size =>
            FinishRollout(container, timeline.liveState.at)

          case RolloutStep(inc) =>
            FinishRolloutStep(container, inc, timeline.liveState.at)

          case RolloutStepComplete(inc, t) if t.plusSeconds(elbSettings.rolloutRate.toSeconds.toInt).isAfter(timeline.liveState.at) =>
            StartRolloutStep(container, registered.size + 1)

          case RolloutStepComplete(inc, t) =>
            WaitingForNextRolloutStep(container, t.plusSeconds(elbSettings.rolloutRate.toSeconds.toInt))

          case RolloutNotStarted =>
            StartRolloutStep(container, registered.size + 1)

          case RolloutComplete(t) => TryNextAction
        }
      }

      maybeAction.getOrElse(TryNextAction)
  }

  val attachNewElb: TimelineData => ActionResultOrNext = {
    timeline =>
      val maybeAction = for {
        nextEnv <- timeline.nextEnvironment orElse timeline.curEnvironment
        elbSettings <- timeline.timelineConfig.externalElb
        container <- nextEnv._2.env
        asg <- container.asg
        if !asg.getLoadBalancerNames.asScala.contains(elbSettings.name)
      } yield AttachElb(container, asg, elbSettings.name)

      maybeAction.getOrElse(TryNextAction)
  }

  val detachOldElb: TimelineData => ActionResultOrNext = {
    timeline =>
      val maybeAction = for {
        curEnv <- timeline.curEnvironment
        if timeline.nextEnvironment.isDefined
        elbSettings <- timeline.timelineConfig.externalElb
        container <- curEnv._2.env
        asg <- container.asg
        if asg.getLoadBalancerNames.asScala.contains(elbSettings.name)
      } yield DetachElb(container, asg, elbSettings.name)

      maybeAction.getOrElse(TryNextAction)
  }

  def deploymentCalls(desiredStateSink: Sink[Task, (TimelineName, DesiredTimelineState)]): Seq[TimelineData => ActionResultOrNext] = Seq(
    dropEnvironment,
    createEnvironment,
    moveARecord,
    updateElbHealthCheck,
    incrementTraffic,
    decrementTraffic,
    updateRolloutState,
    attachNewElb,
    detachOldElb,
    startNextDeployment,
    moveEnvironmentToUnusedOrUsed,
    shutdownEnvironment
  )
}
