package ionroller

import org.joda.time.DateTime
import play.api.libs.json._

sealed trait RolloutStatus

object RolloutStatus {
  implicit lazy val jsonFormat: Format[RolloutStatus] = new Format[RolloutStatus] {
    override def reads(json: JsValue): JsResult[RolloutStatus] = {
      json.validate[JsObject] map {
        o =>
          (
            (o \ "step").asOpt[Int],
            (o \ "completedAt").asOpt[DateTime]
          ) match {
              case (None, None) => RolloutNotStarted
              case (Some(s), None) => RolloutStep(s)
              case (Some(s), Some(t)) => RolloutStepComplete(s, t)
              case (None, Some(t)) => RolloutComplete(t)
            }
      }
    }

    override def writes(o: RolloutStatus): JsValue = {
      o match {
        case RolloutNotStarted => JsObject(Seq.empty)
        case p: RolloutStep => RolloutStep.jsonFormat.writes(p)
        case p: RolloutStepComplete => RolloutStepComplete.jsonFormat.writes(p)
        case p: RolloutComplete => RolloutComplete.jsonFormat.writes(p)
      }
    }
  }
}

final case class RolloutStep(step: Int) extends RolloutStatus

object RolloutStep {
  implicit lazy val jsonFormat: Format[RolloutStep] = Json.format[RolloutStep]
}

final case class RolloutStepComplete(step: Int, completedAt: DateTime) extends RolloutStatus

object RolloutStepComplete {
  implicit lazy val jsonFormat: Format[RolloutStepComplete] = Json.format[RolloutStepComplete]
}

final case class RolloutComplete(completedAt: DateTime) extends RolloutStatus

object RolloutComplete {
  implicit lazy val jsonFormat: Format[RolloutComplete] = Json.format[RolloutComplete]
}

case object RolloutNotStarted extends RolloutStatus