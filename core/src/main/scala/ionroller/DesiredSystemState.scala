package ionroller

import scalaz.Equal

final case class DesiredSystemState(timelines: Map[TimelineName, DesiredTimelineState])

object DesiredSystemState {
  implicit lazy val desiredSystemStateEquality = Equal.equalA[DesiredSystemState]
}
