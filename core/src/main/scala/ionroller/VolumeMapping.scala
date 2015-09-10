package ionroller

import play.api.libs.json._

final case class VolumeMapping(containerPath: String, hostIdentifier: String)

object VolumeMapping {
  implicit lazy val jsonFormat = Json.format[VolumeMapping]
}
