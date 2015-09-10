package ionroller

import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

final case class RuntimeConfiguration(setupRole: String, portMappings: Seq[PortMapping], runArgs: Seq[String], volumeMappings: Seq[VolumeMapping], timestamp: DateTime)

object RuntimeConfiguration {

  implicit lazy val jsonFormat = {
    (
      (__ \ 'setupRole).format[String] and
      (__ \ 'portMappings).format[Seq[PortMapping]] and
      (__ \ 'runArgs).format[Seq[String]] and
      (__ \ 'volumeMappings).formatNullable[Seq[VolumeMapping]].inmap(
        (f: Option[Seq[VolumeMapping]]) => f.getOrElse(Seq.empty),
        (g: Seq[VolumeMapping]) => Some(g)
      ) and
        (__ \ 'timestamp).format[DateTime]
    )(RuntimeConfiguration.apply, unlift(RuntimeConfiguration.unapply))
  }
}