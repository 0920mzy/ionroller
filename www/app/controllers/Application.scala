package controllers

import ionroller._
import ionroller.aws.Dynamo
import play.api.mvc._

import scala.concurrent.Future
import scalaz.concurrent.Task

object Application extends Controller {

  implicit class Task2FutureWrapper[A](task: Task[A]) {

    import scala.concurrent.{Future, Promise}
    import scalaz.{-\/, \/-}

    def runFuture(): Future[A] = {
      val p = Promise[A]()

      task.runAsync {
        case -\/(t) =>
          p.failure(t); ()
        case \/-(r) => p.success(r); ()
      }

      p.future
    }
  }

  implicit def taskToFuture[A](result: Task[A]): Future[A] = result.runFuture

  def index() = Action.async { implicit request =>
    Task(Ok(views.html.Application.index()))
  }

  def services() = Action.async { implicit request =>

    val services =
      Global.backend.configurationManager.configurationSignal.get.map(_.timelines.keys.toSeq)

    for {
      svcs <- services
    } yield Ok(views.html.Application.services(svcs))
  }

  def service(serviceName: String) = Action.async { implicit request =>
    val timelineName = TimelineName(serviceName)

    for {
      config <- Global.backend.configurationManager.configurationSignal.get
      desired <- Global.backend.commandManager.desiredStateSignal.get
      table <- Dynamo.configTable(None)
      configs <- Dynamo.getConfigs(table, serviceName, None, None)
    } yield Ok(views.html.Application.service(serviceName, config.timelines.get(timelineName), desired.timelines.get(timelineName), configs))

  }

  def release(serviceName: String) = Action.async(parse.urlFormEncoded) { implicit request =>
    val timelineName = TimelineName(serviceName)
    val version = request.body.get("version").flatMap(_.headOption).map(ReleaseVersion.apply)

    //TODO get config from db and fix user!!!
    def doReleaseInt(config: Option[TimelineConfiguration]): Option[Task[Unit]] = {
      for {
        v <- version
        c <- config
      } yield Global.backend.commandManager.commandQueue.enqueueOne((timelineName, NewRollout(Release(v, c), Some("web-ui"))))
    }

    val result = for {
      config <- Global.backend.configurationManager.configurationSignal.get.map(_.timelines.get(timelineName))
      release <- doReleaseInt(config) match {
        case None => Task.now(None)
        case Some(t) => t.map(Some.apply)
      }
    } yield release match {
      case None => InternalServerError
      case Some(r) => Redirect("/")
    }

    result
  }

  def drop(serviceName: String) = Action.async(parse.urlFormEncoded) { implicit request =>
    val timelineName = TimelineName(serviceName)
    val version = request.body.get("version").flatMap(_.headOption).map(ReleaseVersion.apply)
    val force = request.body.get("force").flatMap(_.headOption)
    //TODO get config from db and fix user!!!
    def doDropInt(config: Option[TimelineConfiguration]): Option[Task[Unit]] = {
      for {
        v <- version
        f <- force
        c <- config
      } yield Task.now(f.toBoolean).flatMap(b => Global.backend.commandManager.commandQueue.enqueueOne((timelineName, DropEnvironment(v, None, b, Some("web-ui")))))
    }

    val result = for {
      config <- Global.backend.configurationManager.configurationSignal.get.map(_.timelines.get(timelineName))
      release <- doDropInt(config) match {
        case None => Task.now(None)
        case Some(t) => t.map(Some.apply)
      }
    } yield release match {
      case None => InternalServerError
      case Some(r) => Redirect("/")
    }

    result
  }

}
