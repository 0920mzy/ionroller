package ionroller.deployment.eb.v1

import ionroller.{PortMapping, VolumeMapping}
import play.api.libs.functional.syntax._
import play.api.libs.json._

final case class DockerrunAWSJson(
    awsEBDockerrunVersion: String = "1",
    authentication: Option[DockerAWSAuthenticationConfiguration],
    image: DockerAWSImageConfiguration,
    ports: Seq[PortMapping],
    volumes: Seq[VolumeMapping],
    logging: String = "/opt/docker/logs"
) {
  def getName = {
    "Dockerrun.aws.json"
  }
}

final case class DockerAWSAuthenticationConfiguration(bucket: String, key: String)

final case class DockerAWSImageConfiguration(name: String, update: Boolean = true)

object DockerrunAWSJson {

  implicit lazy val PortMappingJsonFormat = new Format[PortMapping] {
    override def reads(json: JsValue): JsResult[PortMapping] = {
      val portMapping = PortMapping(
        (json \ "ContainerPort").as[Int],
        (json \ "HostPort").asOpt[Int].getOrElse((json \ "ContainerPort").as[Int])
      )
      JsSuccess(portMapping)
    }

    override def writes(o: PortMapping): JsValue = {
      Json.obj(
        "ContainerPort" -> o.containerPort,
        "HostPort" -> o.hostPort
      )
    }
  }

  implicit lazy val VolumeMappingJsonFormat = {
    ((JsPath \ "ContainerDirectory").format[String] and
      (JsPath \ "HostDirectory").format[String])(VolumeMapping.apply, unlift(VolumeMapping.unapply))
  }

  implicit lazy val dockerAWSImageConfigJsonFormat = (
    (JsPath \ "Name").format[String] and
    (JsPath \ "Update").format[Boolean]
  )(DockerAWSImageConfiguration.apply, unlift(DockerAWSImageConfiguration.unapply))

  implicit lazy val dockerAWSAuthenticationConfigJsonFormat = (
    (JsPath \ "Bucket").format[String] and
    (JsPath \ "Key").format[String]
  )(DockerAWSAuthenticationConfiguration.apply, unlift(DockerAWSAuthenticationConfiguration.unapply))

  implicit lazy val dockerAWSConfigJsonFormat = (
    (JsPath \ "AWSEBDockerrunVersion").format[String] and
    (JsPath \ "Authentication").formatNullable[DockerAWSAuthenticationConfiguration] and
    (JsPath \ "Image").format[DockerAWSImageConfiguration] and
    (JsPath \ "Ports").format[Seq[PortMapping]] and
    (JsPath \ "Volumes").format[Seq[VolumeMapping]] and
    (JsPath \ "Logging").format[String]
  )(DockerrunAWSJson.apply, unlift(DockerrunAWSJson.unapply))

}

