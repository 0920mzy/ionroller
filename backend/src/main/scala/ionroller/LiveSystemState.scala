package ionroller

import ionroller.tracking.Event

import scalaz.Equal

final case class LiveSystemState(timelines: Map[TimelineName, TimelineState])

object LiveSystemState {
  implicit lazy val liveSystemStateEquality = Equal.equalA[LiveSystemState]

  def diffTimelineState(oldSystemState: LiveSystemState, newSystemState: LiveSystemState): Seq[Event] = {
    val changedTimelines = {
      for {
        k <- (oldSystemState.timelines.keySet ++ newSystemState.timelines.keySet).toSet
        if oldSystemState.timelines.get(k) != newSystemState.timelines.get(k)
      } yield (k, oldSystemState.timelines.get(k), newSystemState.timelines.get(k))
    }
    for {
      (timeline, maybePrevState, maybeCurrState) <- changedTimelines.toSeq
      evt <- LiveTimelineState.compareStates(timeline, maybePrevState, maybeCurrState)
    } yield evt
  }
}
