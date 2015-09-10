package ionroller

import play.api.libs.json._

final case class PortMapping(containerPort: Int, hostPort: Int)

object PortMapping {
  implicit lazy val jsonFormat = Json.format[PortMapping]
}
