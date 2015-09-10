package ionroller

import com.amazonaws.services.elasticbeanstalk.model.ConfigurationOptionSetting
import ionroller.deployment.eb.EBSOptionSettings
import play.api.libs.json._

final case class EBSConfiguration(deploymentBucket: String, solutionStack: String, envOptionSettings: Seq[ConfigurationOptionSetting], resources: Option[JsObject], packages: Option[JsObject], sources: Option[JsObject], files: Option[JsObject], users: Option[JsObject], groups: Option[JsObject], commands: Option[JsObject], containerCommands: Option[JsObject], services: Option[JsObject])

object EBSConfiguration {
  implicit val jsonFormat = Json.format[EBSConfiguration]

  def mergeDefaults(config: EBSConfiguration, serviceRole: String): EBSConfiguration = {
    EBSConfiguration(
      config.deploymentBucket,
      config.solutionStack,
      EBSOptionSettings(serviceRole, config.envOptionSettings),
      Some(ConfigurationManager.defaultResources deepMerge config.resources.getOrElse(JsObject(Seq.empty))),
      config.packages,
      config.sources,
      config.files,
      config.users,
      config.groups,
      config.commands,
      config.containerCommands,
      config.services
    )
  }
}
