package ionroller.deployment.eb

import com.amazonaws.services.elasticbeanstalk.model.ConfigurationOptionSetting
import ionroller.ConfigurationManager

final case class EBSOptionSettings(optionSettings: Seq[ConfigurationOptionSetting])

object EBSOptionSettings {

  def apply(serviceRole: String, environmentOptionSettings: Seq[ConfigurationOptionSetting]) = {

    def defaultOptionSettings = ConfigurationManager.defaultOptionSettings ++ Seq(instanceProfileOption(serviceRole))

    def instanceProfileRole(role: String) = {
      val Pattern = "arn:.*/(.*)".r
      role match {
        case Pattern(r) => r
        case _ => role
      }
    }

    def instanceProfileOption(role: String) = {
      new ConfigurationOptionSetting("aws:autoscaling:launchconfiguration", "IamInstanceProfile", instanceProfileRole(role))
    }

    val defaultsToAdd = for {
      defaultOption <- defaultOptionSettings
      if !environmentOptionSettings.exists(o => o.getNamespace == defaultOption.getNamespace && o.getOptionName == defaultOption.getOptionName)
    } yield defaultOption
    (defaultsToAdd ++ environmentOptionSettings).toSet.toSeq
  }
}