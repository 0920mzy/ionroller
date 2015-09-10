package ionroller

import com.amazonaws.services.elasticloadbalancing.model.InstanceState
import ionroller.tracking._
import org.joda.time.DateTime

import scalaz.NonEmptyList
import scalaz.syntax.std.option._

sealed trait TimelineState

final case class LiveTimelineState(at: DateTime, environments: Seq[ContainerEnvironment], externalElbInstanceStates: Option[Seq[InstanceState]], route53Targets: Seq[String]) extends TimelineState

final case class BrokenTimelineState(at: DateTime, causes: NonEmptyList[Event]) extends TimelineState

object LiveTimelineState {
  def empty = LiveTimelineState(new DateTime(), Seq.empty, None, Seq.empty)

  def compareStates(timeline: TimelineName, maybePrevState: Option[TimelineState], maybeCurrState: Option[TimelineState]): Seq[Event] = {
    (maybePrevState, maybeCurrState) match {
      case (None, None) => Seq.empty
      case (_, Some(BrokenTimelineState(_, events))) => events.list
      case (None, Some(LiveTimelineState(_, envs, _, _))) => {
        // added envs
        val added = for {
          env <- envs
        } yield Event(EnvironmentAdded, timeline, env.setup.dockerImage.tag.some, s"[INFO] Added environment: ${env.environmentDescription.getEnvironmentName}", env.some)
        val instanceEvents = for {
          env: ContainerEnvironment <- envs
          event <- env.events.reverse
        } yield Event(EnvironmentEvent, timeline, env.setup.dockerImage.tag.some, s"[${event.getSeverity}] ${event.getMessage}", event.some)
        added ++ instanceEvents
      }
      case (Some(LiveTimelineState(_, envs, _, _)), None) => {
        // removed envs
        val removed = for {
          env <- envs
        } yield Event(EnvironmentRemoved, timeline, env.setup.dockerImage.tag.some, s"[INFO] Removed environment: ${env.environmentDescription.getEnvironmentName}", env.some)
        removed.toSeq
      }
      case (Some(BrokenTimelineState(_, events)), None) => // prev events have already been emitted. Timeline has been removed.
        Seq(Event(ServiceEvent, timeline, None, "[INFO] ION-Roller setup removed.", None))
      case (Some(BrokenTimelineState(_, oldEvent)), Some(LiveTimelineState(_, newEnvironments, _, _))) => // events will be emitted during next loop
        Seq(Event(ServiceEvent, timeline, None, "[INFO] ION-Roller processing resumed.", None)) // TODO BrokenTimelineState should keep the events from last healthy state
      case (Some(LiveTimelineState(_, oldEnvironments, _, _)), Some(LiveTimelineState(_, newEnvironments, _, _))) => {
        val newEnvs = newEnvironments.groupBy(_.environmentDescription.getEnvironmentId)
        val oldEnvs = oldEnvironments.groupBy(_.environmentDescription.getEnvironmentId)
        val removedEnvs = for {
          (k, v) <- oldEnvs
          if !newEnvs.get(k).isDefined
          env <- v
        } yield Event(EnvironmentRemoved, timeline, env.setup.dockerImage.tag.some, s"[INFO] Removed environment: ${env.environmentDescription.getEnvironmentName}", env.some)
        val addedEnvs = for {
          (k, v) <- newEnvs
          if !oldEnvs.get(k).isDefined
          env <- v
        } yield Event(EnvironmentAdded, timeline, env.setup.dockerImage.tag.some, s"[INFO] Added environment: ${env.environmentDescription.getEnvironmentName}", env.some)
        val addedEnvsEvents = for {
          (k, v) <- newEnvs
          if !oldEnvs.get(k).isDefined
          env <- v
          event <- env.events.reverse
        } yield Event(EnvironmentEvent, timeline, env.setup.dockerImage.tag.some, s"[${event.getSeverity}] ${event.getMessage}", event.some)
        val healthChangedEnvs = for {
          (k, v) <- newEnvs
          oldEnvs <- oldEnvs.get(k).toList
          oldEnv <- oldEnvs
          env <- v
          if ((env.environmentDescription.getHealth != oldEnv.environmentDescription.getHealth)
            && env.environmentDescription.getHealth.toLowerCase == "green"
            && oldEnv.environmentDescription.getHealth.toLowerCase == "grey")
        } yield Event(EnvironmentHealthChanged, timeline, env.setup.dockerImage.tag.some, s"[INFO] Environment health has transitioned from ${oldEnv.environmentDescription.getHealth.toUpperCase} to ${env.environmentDescription.getHealth.toUpperCase}", env.some)
        val stateChangedEnvs = for {
          (k, v) <- newEnvs
          oldEnvs <- oldEnvs.get(k).toList
          oldEnv <- oldEnvs
          env <- v
          if env.environmentDescription.getStatus != oldEnv.environmentDescription.getStatus
        } yield Event(EnvironmentStatusChanged, timeline, env.setup.dockerImage.tag.some, s"[INFO] Environment status has changed from ${oldEnv.environmentDescription.getStatus} to ${env.environmentDescription.getStatus}", env.some)
        val envEvents = for {
          (k, v) <- newEnvs
          oldEnvs <- oldEnvs.get(k).toList
          oldEnv <- oldEnvs
          env <- v
          event <- env.events.reverse.filterNot({
            case e => oldEnv.events.contains(e)
          })
        } yield Event(EnvironmentEvent, timeline, env.setup.dockerImage.tag.some, s"[${event.getSeverity}] ${event.getMessage}", event.some)
        (removedEnvs ++ addedEnvs ++ addedEnvsEvents ++ stateChangedEnvs ++ healthChangedEnvs ++ envEvents).toSeq
      }
    }
  }
}
