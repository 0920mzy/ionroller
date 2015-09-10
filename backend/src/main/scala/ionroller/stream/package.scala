package ionroller

import com.typesafe.scalalogging.Logger
import play.api.libs.json.Json

import scalaz.Scalaz._
import scalaz._
import scalaz.concurrent.Task
import scalaz.stream.Cause.EarlyCause
import scalaz.stream.Process.{Await, Emit, Halt, Step}
import scalaz.stream._

trait Stepper[F[_], A] {
  def next: OptionT[F, Seq[A]]
  def close: F[Unit]
}

package object stream {
  import JsonUtil.Implicits._

  implicit class RatelimitedProcessSyntax[O](p: Process[Task, O]) {
    def logDebug(logger: Logger, f: O => String) = {
      val logStringProcess: Sink[Task, String] = Process.constant({ i: String => Task.delay(logger.debug(i)) })
      p.observe(logStringProcess.contramap(f))
    }

    def logInfo(logger: Logger, f: O => String) = {
      val logStringProcess: Sink[Task, String] = Process.constant({ i: String => Task.delay(logger.info(i)) })
      p.observe(logStringProcess.contramap(f))
    }

    def logDebugJson(logger: Logger) =
      logDebug(logger, i => Json.prettyPrint(i.toJsonValue))

    def logThrowable[T](logger: Logger, msg: String)(implicit ev: O <:< \/[Throwable, T]): Process[Task, T] = {

      val logThrowableProcess = Process.constant({ i: \/[Throwable, T] =>
        i match {
          case -\/(t) => Task.delay(logger.error(msg, t))
          case _ => Task.now(())
        }
      })

      p.map(ev.apply).observe(logThrowableProcess).collect {
        case \/-(v) => v
      }
    }
  }

  def emitChanges[A, B](differ: (A, A) => Seq[B])(implicit equal: Equal[A]): Process1[A, B] = {
    def emitChangesProcess(items: Vector[A]) = {
      if (items.size <= 1)
        Process.empty
      else
        Process.emitAll(differ(items(0), items(1)))
    }

    process1.distinctConsecutive[A].sliding(2).flatMap(emitChangesProcess)
  }

  def step[A](p: Process[Task, A]): Stepper[Task, A] = new Stepper[Task, A] {
    var state = p

    def next: OptionT[Task, Seq[A]] = state.step match {

      case Halt(_) => OptionT.none

      case Step(Emit(as: Seq[A]), cont) =>
        state = cont.continue
        OptionT(as.point[Task] map some)

      case Step(Await(req: Task[_] @unchecked, rcv), cont) =>
        for {
          tail <- (req.attempt map { r => rcv(EarlyCause fromTaskResult r).run +: cont }).liftM[OptionT]
          _ = state = tail
          back <- next
        } yield back
    }

    def close =
      Task.suspend {
        Task.delay(state = state.kill) >>
          state.run
      }
  }
}
