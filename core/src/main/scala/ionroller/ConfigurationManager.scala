package ionroller

import com.amazonaws.services.elasticbeanstalk.model.ConfigurationOptionSetting
import com.typesafe.config.{Config, ConfigException, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging
import ionroller.aws.Dynamo
import play.api.libs.json._

import scalaz.concurrent.Task
import scalaz.stream._
import scalaz.{-\/, \/-}

class ConfigurationManager(initialConfig: SystemConfiguration) extends StrictLogging {
  val configurationSignal = async.signalOf(initialConfig)
}

object ConfigurationManager extends StrictLogging {
  def apply(initialConfig: SystemConfiguration): ConfigurationManager =
    new ConfigurationManager(initialConfig)

  val confFile: Config = ConfigFactory.load()
  val defaultSolutionStack: String = confFile.getString("ionroller.solution-stack-name")
  val whitelistKey = "ionroller.modify-environments-whitelist"
  val blacklistKey = "ionroller.modify-environments-blacklist"

  val modifyEnvironmentslist: (String, Boolean) => Set[TimelineName] = {
    (key, required) =>
      try {
        confFile.getString(key)
          .split(",")
          .map(_.trim)
          .collect({ case s: String if !s.isEmpty && s != "ALL" => s })
          .map(TimelineName.apply).toSet
      } catch {
        case ex: ConfigException.Missing => {
          if (required) {
            logger.error(s"${key} $required configuration is missing.\nRun ION-Roller with property: -D${key}=[ALL|<TIMELINE_NAME_1,TIMELINE_NAME_2,...>]")
            throw ex
          } else Set.empty
        }
      }
  }

  val modifyEnvironmentsWhitelist = modifyEnvironmentslist(whitelistKey, true)
  val modifyEnvironmentsBlacklist = modifyEnvironmentslist(blacklistKey, false)

  logger.debug("Processing timelines: " + {
    if (modifyEnvironmentsWhitelist.isEmpty) "ALL" else modifyEnvironmentsWhitelist
  }) + {
    if (!modifyEnvironmentsBlacklist.isEmpty) "excluding: " + modifyEnvironmentsBlacklist else ""
  }
  val modifyEnvironments = confFile.getBoolean("ionroller.modify-environments")

  val defaultOptionSettings: Seq[ConfigurationOptionSetting] = {
    val options = confFile.getString("ionroller.option-settings")

    Task(Json.parse(options).as[Seq[ConfigurationOptionSetting]]).attemptRun match {
      case \/-(settings) => settings
      case -\/(t) => {
        logger.error(t.getMessage, t)
        Seq.empty
      }
    }
  }

  val defaultResources: JsObject = {
    val resources = confFile.getString("ionroller.resources")

    Task(Json.parse(resources).as[JsObject]).attemptRun match {
      case \/-(resources) => resources
      case -\/(t) => {
        logger.error(t.getMessage, t)
        JsObject(Seq.empty)
      }
    }
  }

  def getSavedConfiguration: Task[SystemConfiguration] = {
    for {
      table <- Dynamo.configTable(None)
      systemConfig <- Dynamo.getSystemConfig(table)
    } yield systemConfig
  }

}