package ionroller

import play.api.libs.json._

final case class EBSSetup(dockerImage: DockerImage, config: RuntimeConfiguration, ebsConfiguration: EBSConfiguration)

object EBSSetup {
  implicit lazy val jsonFormat = Json.format[EBSSetup]

  def sameEnvironment(env1: EBSSetup, env2: EBSSetup) = {
    env1.dockerImage == env2.dockerImage && env1.config.timestamp.isEqual(env2.config.timestamp)
  }
}
