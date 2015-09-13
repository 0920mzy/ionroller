package controllers

import com.amazonaws.services.dynamodbv2.document.DeleteItemOutcome
import com.gilt.ionroller.api.v1.models
import com.gilt.ionroller.api.v1.models.json._
import com.typesafe.scalalogging.StrictLogging
import controllers.ApidocImplicits._
import ionroller._
import ionroller.aws.Dynamo
import ionroller.tracking.{ConfigurationChanged, Event}
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scalaz._
import scalaz.concurrent.Task
import scalaz.std.list._
import scalaz.std.option._
import scalaz.syntax.applicative._
import scalaz.syntax.either._

class UserRequest[A](val user: Option[String], request: Request[A]) extends WrappedRequest[A](request)

class AuthAction(checkAllowed: String => Future[Boolean]) extends ActionFilter[UserRequest] {

  private[controllers] def allowedToResult(allowed: Boolean) = {
    if (allowed)
      None
    else
      Some(Results.Forbidden)
  }

  def filter[A](input: UserRequest[A]) = {
    input.user match {
      case None => Future.successful(Some(Results.Unauthorized))
      case Some(details) => checkAllowed(details) map allowedToResult
    }
  }
}

object UserAction extends ActionBuilder[UserRequest] with ActionTransformer[Request, UserRequest] {

  def username(request: RequestHeader) = {
    request.headers.get("User").flatMap(u => u.trim match {
      case s if s.length > 0 => Some(s)
      case s => None
    })
  }

  def transform[A](request: Request[A]) = Future.successful {
    new UserRequest(username(request), request)
  }
}

trait AuthenticatedController {
  def checkAllowed(user: String): Future[Boolean] = Future {
    true
  } // TODO verify user

  def AuthAction = UserAction andThen new AuthAction(checkAllowed)
}

object Services extends Controller with StrictLogging {
  import JsonUtil.Implicits._

  val futureToTask = new (Future ~> Task) {
    def apply[A](f: Future[A]): Task[A] = {
      Task.async { cb =>
        f.onComplete {
          case scala.util.Success(r) => cb(r.right)
          case scala.util.Failure(t) => cb(t.left)
        }
      }
    }
  }

  implicit val taskToFuture = new (Task ~> Future) {
    def apply[A](t: Task[A]): Future[A] = {
      val p = Promise[A]()

      t.runAsync {
        case -\/(t) =>
          p.failure(t); ()
        case \/-(r) => p.success(r); ()
      }

      p.future
    }
  }

  implicit def gtToFuture[A, B[_]](result: B[A])(implicit ev: B ~> Future): Future[A] =
    ev(result)

  implicit def environmentToModel(env: ionroller.ContainerEnvironment): models.Environment = {
    models.Environment(
      models.Release(env.setup.dockerImage.repository.toString, env.setup.dockerImage.tag.tag, env.setup.config.timestamp),
      if (env.healthy) {
        "Healthy"
      } else if (env.unused.isDefined) {
        "Unused"
      } else {
        "Unhealthy"
      }
    )
  }

  implicit def liveStateToModel(s: ionroller.TimelineState): models.ServiceCurrentState = {
    s match {
      case LiveTimelineState(_, envs, _, _) => models.ServiceCurrentState(envs.map(environmentToModel))
      case BrokenTimelineState(_, evt) => models.ServiceCurrentState(Seq.empty)
    }
  }

  def setConfigForTimeline(
    timelineName: TimelineName,
    config: ionroller.TimelineConfiguration
  )(current: Option[ionroller.SystemConfiguration]): Option[ionroller.SystemConfiguration] = {
    current match {
      case None => None
      case Some(c) =>
        Some(c.copy(timelines = c.timelines.updated(timelineName, config)))
    }
  }

  def deleteConfigForTimeline(
    timelineName: TimelineName
  )(current: Option[ionroller.SystemConfiguration]): Option[ionroller.SystemConfiguration] = {
    current match {
      case None => None
      case Some(c) =>
        Some(c.copy(timelines = c.timelines.filterKeys(_ != timelineName)))
    }
  }

  def post() = Action {
    NotImplemented
  }

  def get =
    Action.async { implicit request =>
      Global.backend.pipeline.liveStateSignal.get.map {
        current =>
          Ok(current.toJsonValue)
      }
    }

  def getByServiceName(serviceName: String) = Action.async { implicit request =>
    val timelineName = TimelineName(serviceName)
    ^^(
      Global.backend.configurationManager.configurationSignal.get,
      Global.backend.pipeline.liveStateSignal.get,
      Global.backend.commandManager.desiredStateSignal.get
    ) {
      (config, current, desired) =>
        (config.timelines.get(timelineName), current.flatMap(_.timelines.get(timelineName)), desired.timelines.get(timelineName)) match {
          case (Some(config), c, d) => Ok(Json.toJson(
            models.Service(
              serviceName,
              models.ServiceState(
                liveStateToModel(c.getOrElse(LiveTimelineState.empty)),
                d.toApidoc
              )
            )
          ))
          case (None, _, _) => NotFound
        }
    }
  }

  def putConfigByServiceName(serviceName: String) =
    UserAction.async(parse.json) { implicit request =>
      val timelineName = TimelineName(serviceName)

      request.body.validate[models.ServiceConfig] match {
        case JsSuccess(config, path) => {

          val cfg = config.fromApidoc

          val checkForErrors =
            Global.backend.configurationManager.configurationSignal
              .get.map(curConfig => curConfig.timelines.filterNot(_._1 == timelineName).exists(_._2.url == config.url))

          val updateInternalSignal =
            Global.backend.configurationManager.configurationSignal
              .compareAndSet(setConfigForTimeline(timelineName, cfg))

          for {
            table <- Dynamo.configTable(None)
            errors <- checkForErrors
            config <- Task(cfg)
            save <- {
              if (errors) {
                Task.now(BadRequest("Duplicate URL"))
              } else {
                Dynamo.saveConfig(table, serviceName, config).flatMap(_ => updateInternalSignal)
                  .flatMap(_ => logEvent(Event(ConfigurationChanged, TimelineName(serviceName), None, s"[INFO] Configuration changed.", Some(Json.toJson(config.toApidoc)), user = request.user)))
                  .map(_ => NoContent)
              }
            }
          } yield save
        }
        case JsError(errors) => Task.now(BadRequest(errors.toString()))
      }
    }

  def getConfigByServiceName(serviceName: String) = Action.async {
    implicit request =>
      val timelineName = TimelineName(serviceName)

      Global.backend.configurationManager.configurationSignal.get.map {
        s =>

          s.timelines.get(timelineName) match {
            case None => NotFound
            case Some(r) => {
              Ok(Json.toJson(r.toApidoc))
            }
          }
      }
  }

  def getConfigsByServiceName(serviceName: String, from: Option[DateTime], to: Option[DateTime]) = Action {
    val config = for {
      table <- Dynamo.configTable(None)
      config <- Dynamo.getConfigs(table, serviceName, from, to)
    } yield config
    config.attemptRun match {
      case -\/(f) =>
        logger.error(f.getMessage, f)
        NoContent
      case \/-(cfg) => Ok(Json.toJson(cfg.toList.toApidoc))
    }
  }

  def getConfigsByServiceNameAndTimestamp(serviceName: String, timestamp: DateTime) = Action {
    val config = for {
      table <- Dynamo.configTable(None)
      config <- Dynamo.getConfig(table, serviceName, timestamp)
    } yield config
    config.attemptRun match {
      case -\/(f) =>
        logger.error(f.getMessage, f)
        NoContent
      case \/-(cfg) => cfg.fold(NoContent)(cfg => Ok(Json.toJson(cfg.toApidoc)))
    }
  }

  implicit val jsonReadsJodaDateTime = __.read[String].map {
    str =>

      import org.joda.time.format.ISODateTimeFormat.dateTimeParser

      dateTimeParser.parseDateTime(str)
  }

  def deleteConfigByServiceName(serviceName: String) = Action {
    val deleteConfigTask = for {
      desiredSystemState <- Global.backend.commandManager.desiredStateSignal.get.map(_.timelines.get(TimelineName(serviceName)))
      currConfig <- Global.backend.configurationManager.configurationSignal.get.map(_.timelines.get(TimelineName(serviceName)))
      deleteItemOutcome <- {
        currConfig match {
          case Some(conf) =>
            desiredSystemState.flatMap(s => s.curEnvironment) match {
              case Some(env) =>
                Task.now(PreconditionFailed("Service must have no running instances"))
              case None => {
                for {
                  table <- Dynamo.configTable(None)
                  delete <- Dynamo.deleteConfig(table, serviceName)
                  _ <- Global.backend.configurationManager.configurationSignal.compareAndSet(deleteConfigForTimeline(TimelineName(serviceName)))
                } yield NoContent
              }
            }
          case None => Task.now(NotFound)
        }
      }
    } yield deleteItemOutcome

    deleteConfigTask.attemptRun match {
      case -\/(f) =>
        logger.error(f.getMessage, f)
        InternalServerError
      case \/-(result) => result
    }
  }

  def postReleaseByServiceName(serviceName: String) =
    UserAction.async(parse.json) {
      implicit request =>
        val timelineName = TimelineName(serviceName)

        val verAndConfig = (
          (request.body \ "version").validate[String] and
          ((request.body \ "config").toOption match {
            case None => JsSuccess(None)
            case Some(JsNull) => JsSuccess(None)
            case Some(c) => c.validate[DateTime].map(Option.apply)
          })
        )((_, _))

        verAndConfig match {
          case JsSuccess((release, configTimestamp), _) => {
            for {
              cfg <- getConfig(serviceName, configTimestamp)
              result <- {
                cfg match {
                  case Some(c) =>
                    val time = (DateTime.now().getMillis * 1000).toString
                    val r = Release(ReleaseVersion(release), c)
                    Global.backend.commandManager.commandQueue.enqueueOne((timelineName, NewRollout(r, request.user))).map(s => Ok(time))
                  case None => Task.now(BadRequest(s"Configuration $configTimestamp not found"))
                }
              }
            } yield result
          }
          case JsError(errors) => Task.now(BadRequest(errors.toString()))
        }
    }

  def getConfig(service: String, timestamp: Option[DateTime]): Task[Option[TimelineConfiguration]] = {
    for {
      configFromSignal <- Global.backend.configurationManager.configurationSignal.get.map(_.timelines.get(TimelineName(service)))
      config <- {
        (timestamp, configFromSignal) match {
          case (None, None) => Task.now(None)
          case (None, Some(c)) => Task.delay(Some(c))
          case (Some(t), Some(c)) if t == c.timestamp => Task.delay(Some(c))
          case (Some(t), Some(c)) => Dynamo.getConfig(service, t)
        }
      }
    } yield config
  }

  def postDropByServiceName(serviceName: String) =
    UserAction.async(parse.json) {
      implicit request =>
        val timelineName = TimelineName(serviceName)

        val verAndConfigAndForce = (
          (request.body \ "version").validate[String] and
          ((request.body \ "config").toOption match {
            case None => JsSuccess(None)
            case Some(JsNull) => JsSuccess(None)
            case Some(c) => c.validate[DateTime].map(Option.apply)
          }) and
          (request.body \ "force").validate[Boolean]
        )((_, _, _))

        verAndConfigAndForce match {
          case JsSuccess((releaseVersion, configTimestamp, force), path) => {
            val time = (DateTime.now().getMillis * 1000).toString

            configTimestamp match {
              case Some(c) =>
                Dynamo.getConfig(serviceName, c).flatMap {
                  case Some(conf) => Global.backend.commandManager.commandQueue.enqueueOne((timelineName, DropEnvironment(ReleaseVersion(releaseVersion), configTimestamp, force, request.user))).map {
                    s =>
                      Ok(time)
                  }
                  case None => Task.now(BadRequest(s"Configuration $configTimestamp not found"))
                }
              case None =>
                Global.backend.commandManager.commandQueue.enqueueOne((timelineName, DropEnvironment(ReleaseVersion(releaseVersion), configTimestamp, force, request.user))).map {
                  s =>
                    Ok(time)
                }
            }
          }
          case JsError(errors) => Task.now(BadRequest(errors.toString()))
        }
    }

}
