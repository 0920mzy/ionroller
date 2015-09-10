package ionroller

import java.util.concurrent.TimeUnit

import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.FiniteDuration

final case class SystemConfiguration(timelines: Map[TimelineName, TimelineConfiguration])

final case class ExternalElbSettings(name: String, securityGroup: String, rolloutRate: FiniteDuration)

object ExternalElbSettings {

  implicit val durationFormat = new Format[FiniteDuration] {
    override def writes(o: FiniteDuration): JsValue = Json.toJson(o.toSeconds)

    override def reads(json: JsValue): JsResult[FiniteDuration] =
      json.validate[Long].map(FiniteDuration.apply(_, TimeUnit.SECONDS))
  }

  implicit lazy val jsonFormat = Json.format[ExternalElbSettings]
}

final case class TimelineConfiguration(
    url: String,
    hostedZoneId: String,
    externalElb: Option[ExternalElbSettings],
    dockerImage: DockerRepository,
    awsAccountId: String,
    serviceRole: String,
    portMappings: Seq[PortMapping],
    volumeMappings: Seq[VolumeMapping],
    runArgs: Seq[String],
    removeUnusedAfter: Option[FiniteDuration],
    private val _ebsConfig: EBSConfiguration,
    timestamp: DateTime
) {
  val ebsConfig = EBSConfiguration.mergeDefaults(_ebsConfig, serviceRole)
  val ionrollerRoleArn = ionrollerRole(awsAccountId)
}

object TimelineConfiguration {

  implicit lazy val jsonFormat = {
    (
      (__ \ 'url).format[String] and
      (__ \ 'hostedZoneId).format[String] and
      (__ \ 'externalElb).formatNullable[ExternalElbSettings] and
      (__ \ 'dockerImage).format[DockerRepository] and
      (__ \ 'awsAccountId).format[String] and
      (__ \ 'serviceRole).format[String] and
      (__ \ 'portMappings).format[Seq[PortMapping]] and
      (__ \ 'volumeMappings).formatNullable[Seq[VolumeMapping]].inmap(
        (f: Option[Seq[VolumeMapping]]) => f.getOrElse(Seq.empty),
        (g: Seq[VolumeMapping]) => Some(g)
      ) and
        (__ \ 'runArgs).format[Seq[String]] and
        (__ \ 'removeUnusedAfter).formatNullable[FiniteDuration] and
        (__ \ '_ebsConfig).format[EBSConfiguration] and
        (__ \ 'timestamp).format[DateTime]
    )(TimelineConfiguration.apply, unlift(TimelineConfiguration.unapply))
  }
}
