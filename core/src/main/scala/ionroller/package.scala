import java.util.concurrent.{ExecutorService, Executors, ScheduledExecutorService}

import com.amazonaws.services.elasticbeanstalk.model.ConfigurationOptionSetting
import com.typesafe.scalalogging.StrictLogging
import ionroller.aws.Dynamo
import ionroller.tracking.Event
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.FiniteDuration
import scalaz.concurrent.Task
import scalaz.{-\/, \/-}

package object ionroller extends StrictLogging {
  val ionrollerExecutorService: ExecutorService = Executors.newFixedThreadPool(4)

  implicit val `| Implicit executor service        |`: ExecutorService = ionrollerExecutorService
  implicit val ` | is disabled - define explicitly  |`: ExecutorService = ionrollerExecutorService

  implicit val timer: ScheduledExecutorService = scalaz.concurrent.Strategy.DefaultTimeoutScheduler

  def ionrollerRole(awsAccountId: String) = s"arn:aws:iam::$awsAccountId:role/ionroller"

  implicit lazy val finiteDurationFormat = {

    def applyFiniteDuration(l: Long, u: String): FiniteDuration = {
      FiniteDuration(l, u.toLowerCase)
    }

    def unapplyFiniteDuration(d: FiniteDuration): (Long, String) = {
      (d.length, d.unit.toString)
    }

    ((JsPath \ "length").format[Long] and
      (JsPath \ "unit").format[String])(applyFiniteDuration, unapplyFiniteDuration)
  }

  implicit lazy val configurationOptionSettingFormat: Format[ConfigurationOptionSetting] = {
    def applyConfigOptionSetting(ns: String, optionName: String, value: String) =
      new ConfigurationOptionSetting(ns, optionName, value)

    def unapplyConfigOptionSetting(o: ConfigurationOptionSetting): Option[(String, String, String)] = {
      for {
        ns <- Option(o.getNamespace)
        n <- Option(o.getOptionName)
        v <- Option(o.getValue)
      } yield (ns, n, v)
    }

    ((JsPath \ "Namespace").format[String] and
      (JsPath \ "OptionName").format[String] and
      (JsPath \ "Value").format[String])(applyConfigOptionSetting _, unlift(unapplyConfigOptionSetting))
  }

  def enabled(name: TimelineName) = {
    ConfigurationManager.modifyEnvironments &&
      (ConfigurationManager.modifyEnvironmentsWhitelist.isEmpty || ConfigurationManager.modifyEnvironmentsWhitelist.contains(name)) &&
      !ConfigurationManager.modifyEnvironmentsBlacklist.contains(name)
  }

  def logEvent(evt: Event) = {
    logger.info(s"$evt (enabled = ${enabled(evt.service)})")
    if (enabled(evt.service))
      Dynamo.EventLogger.log(evt)
        .flatMap({
          case \/-(s) => Task.now(())
          case -\/(f) => Task.delay(logger.error(f.getMessage, f))
        })
    else Task.now(())
  }

}
