package ionroller.cmd

import org.fusesource.jansi.Ansi

import scala.concurrent.duration.FiniteDuration
import scalaz._
import scalaz.Scalaz._
import scalaz.concurrent.Task
import scalaz.stream.Cause.End
import scalaz.stream.Process.Halt
import scalaz.stream.ReceiveY._
import scalaz.stream._

object ProgressMessage {

  implicit val timeout = scalaz.concurrent.Strategy.DefaultTimeoutScheduler

  def eitherHaltBoth[I, I2]: Wye[I, I2, I \/ I2] =
    wye.receiveBoth {
      case ReceiveL(i) => Process.emit(i.left) ++ eitherHaltBoth
      case ReceiveR(i) => Process.emit(i.right) ++ eitherHaltBoth
      case HaltL(End) => Halt(End)
      case HaltR(End) => Halt(End)
      case h @ HaltOne(rsn) => Halt(rsn)
    }

  def processingMessage(i: Int) = {
    val frames = Array(
      " :O                ",
      "   \u00D6               ",
      "   O:             ",
      "      O\u0324            ",
      "       :O          ",
      "         \u00D6         ",
      "           O:      ",
      "             O\u0324     ",
      "              :O   ",
      "                \u00D6  ",
      "                 O:",
      "                \u00D6  ",
      "              :O   ",
      "             O\u0324     ",
      "           O:      ",
      "         \u00D6         ",
      "       :O          ",
      "      O\u0324            ",
      "    O:             ",
      "   \u00D6               "
    )
    Ansi.ansi().eraseLine().a(frames(i)).cursorUp(1).toString
  }

  def progressMessage(p: Process[Task, String], timeoutDuration: FiniteDuration) = {
    p.wye(time.awakeEvery(timeoutDuration).zipWithIndex.map(_._2 % 20))(ProgressMessage.eitherHaltBoth).pipe(process1.sliding(2)).flatMap { w =>
      val m: Seq[String] =
        if (w.size == 1)
          w(0).fold(l => l, r => processingMessage(r)).some.toSeq
        else if (w.forall(_.isRight)) {
          // Two timeout messages in a row!
          w.headOption.map(_.fold(l => ???, r => processingMessage(r))).toSeq
        } else {
          // No timeout...
          w.filter(_.isLeft).map(_.fold(l => l, r => ???))
        }
      Process.emitAll(m)
    }
      .pipe(process1.distinctConsecutive[String])
  }
}
