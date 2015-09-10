package controllers

import java.util.Date

import com.amazonaws.services.dynamodbv2.document.Item
import ionroller.aws.Dynamo
import ionroller.stream._
import play.api.Logger
import play.api.libs.EventSource
import play.api.libs.EventSource.{EventDataExtractor, EventIdExtractor}
import play.api.libs.iteratee.{Enumerator, Input}
import play.api.mvc._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scalaz.concurrent.Task
import scalaz.stream._
import scalaz.stream.async.mutable.Signal
import scalaz.{-\/, \/-}

object Events extends Controller {

  implicit class Task2FutureWrapper[A](task: Task[A]) {

    import scala.concurrent.{Future, Promise}
    import scalaz.{-\/, \/-}

    def runFuture(): Future[A] = {
      val p = Promise[A]()
      task.runAsync {
        case -\/(t) => {
          Logger.error("Error running task: " + t.printStackTrace(), t)
          p.failure(t)
          ()
        }
        case \/-(r) => {
          p.success(r)
          ()
        }
      }
      p.future
    }
  }

  implicit def taskToFuture[A](result: Task[A]): Future[A] = result.runFuture

  val servicesFromConfigSignal = async.signalOf(Dynamo.getServiceNames.run)

  implicit val timeoutScheduler = scalaz.concurrent.Strategy.DefaultTimeoutScheduler
  implicit val eventIdExtractor = EventIdExtractor[Item](i => Some(i.getString("timestamp")))
  implicit val eventDataExtractor = EventDataExtractor[Item](i => Dynamo.itemToEventJsonString(i))

  def index = Action {
    Ok(views.html.Application.events())
  }

  def eventFeed(from: Option[Long], to: Option[Long], service: Option[String], version: Option[String]) = Action {
    implicit req =>
      {
        val servicesSignalFromSocket = async.signalOf[Option[List[String]]](service.map(_.split(",").toList))
        val lastEventId: Option[Long] = req.headers.get("Last-Event-ID").map(_.toLong)
        @volatile
        var fromTimestamp: Option[Long] = lastEventId.orElse(from)
        val toTimestamp: Option[Long] = to
        val feedStartedAt: Long = new Date().getTime * 1000
        val events: Process[Task, Option[Item]] = for {
          services <- getServices(servicesSignalFromSocket.continuous.once, servicesFromConfigSignal.continuous.once)
          items <- {
            getEvents(services, fromTimestamp, toTimestamp, version).map(list => {
              val nl = (toTimestamp, list.isEmpty) match {
                // we emit CLOSE to close connection on client side
                case (Some(ts), _) => List(Some(new Item().withString("type", "close").withNumber("timestamp", ts)), None /*This ends the stream in Enumerator*/ )
                // we emit AWAIT just to make sure that client is still alive
                case (None, true) => List(Some(new Item().withString("type", "await").withNumber("timestamp", fromTimestamp.getOrElse(feedStartedAt))))
                case (_, _) => List.empty
              }
              list.map({ i: Item => Some(i) }) ++ nl
            })
          }
          item <- Process.emitAll(items)
        } yield {
          (to, item) match {
            case (None, Some(i: Item)) => fromTimestamp = Some(i.getLong("timestamp"))
            case (Some(t), _) => fromTimestamp = Some(t)
            case (_, _) =>
          }
          item
        }

        val eventsStreamProcess = (events ++ time.sleep(5.second)).repeat.takeWhile(_.isDefined).map(_.get)
        val stepper = step(eventsStreamProcess)
        val e = Enumerator.fromCallback1(b => stepper.next.fold(t => Some(t), None), () => stepper.close, (str, inp: Input[Seq[Item]]) => stepper.close)
        (to, lastEventId) match {
          case (Some(t), Some(id)) if to == lastEventId => NoContent
          case (_, _) => Ok.feed(e.flatMap(seq => Enumerator.enumerate(seq)) &> EventSource()).as("text/event-stream")
        }
      }
  }

  def getServices(processA: => Process[Task, Option[List[String]]], processB: => Process[Task, List[String]]): Process[Task, List[String]] = {
    for {
      option <- processA
      process <- option match {
        case Some(o) => Process.emit(o)
        case None => processB
      }
    } yield process
  }

  def getEvents(services: List[String], fromTimestamp: Option[Long], toTimestamp: Option[Long], version: Option[String]) = {
    (fromTimestamp, toTimestamp) match {
      case (Some(f), Some(t)) if f == t => Process.empty
      case (_, _) => {
        Dynamo.queryEvents(services, fromTimestamp, toTimestamp, version, Some(20)).attemptRun match {
          case -\/(f) => {
            Logger.error("Error querying events: ", f)
            time.sleep(10.seconds)
          }
          case \/-(i) => Process.emit(i)
        }
      }
    }
  }

  def updateServicesFromConfigSignal() = {
    time.awakeEvery(1.minutes)
      .flatMap(_ => Process.eval_(Dynamo.getServiceNames))
      .map(Signal.Set(_))
      .to(servicesFromConfigSignal.sink)
      .run
  }

  updateServicesFromConfigSignal().runAsyncInterruptibly {
    case -\/(t) => Logger.error("Error reading service names from config", t)
    case \/-(u) =>
  }
}
