package ionroller

import com.amazonaws.services.dynamodbv2.document.PutItemOutcome
import com.typesafe.scalalogging.StrictLogging
import ionroller.aws.Dynamo
import ionroller.stream._
import ionroller.tracking.{CommandEvent, CommandIgnoredEvent, Event, EventType}
import org.joda.time.format.ISODateTimeFormat
import play.api.libs.json._

import scalaz.\/
import scalaz.concurrent.Task
import scalaz.stream._
import scalaz.stream.async.mutable.Signal
import scalaz.syntax.std.option._

final case class DesiredStateInput(name: TimelineName, cmd: RolloutCommand, enabled: Boolean)

/**
 *
 * @param configurationSignal The configuration signal (system configuration)
 */
class CommandManager(configurationSignal: Signal[SystemConfiguration], initialState: DesiredSystemState) extends StrictLogging {
  val commandQueue = async.boundedQueue[(TimelineName, RolloutCommand)](500)
  val desiredStateSignal = async.signalOf(initialState)

  val sink: Sink[Task, (TimelineName, RolloutCommand)] = commandQueue.enqueue

  def commandIgnoredReason(in: DesiredStateInput, des: Option[DesiredTimelineState]): String = {
    des match {
      case Some(s) => (in.cmd, s.curEnvironment, s.nextEnvironment, s.futureEnvironment) match {
        case (cmd: NewRollout, Some(c), _, _) if c.dockerImage.tag == cmd.release.tag && cmd.release.config.timestamp == c.config.timestamp => " as it is currently deployed."
        case (cmd: NewRollout, _, Some(n), _) if n.dockerImage.tag == cmd.release.tag && cmd.release.config.timestamp == n.config.timestamp => " as it is already scheduled for next deployment."
        case (cmd: NewRollout, _, _, Some(f)) if f.dockerImage.tag == cmd.release.tag && cmd.release.config.timestamp == f.config.timestamp => " as it is already scheduled for future deployment."
        case (cmd: NewRollout, _, _, _) => " as config timestamp does not exist."
        case (cmd: DropEnvironment, _, _, _) => " as there is nothing to drop."
        case (_, _, _, _) => ""
      }
      case None => " No such service."
    }
  }

  val logCommandEvent: EventType => Sink[Task, (Option[DesiredStateInput], Option[DesiredSystemState])] = { e =>

    implicit val EnvironmentJsonFormat = new Writes[EBSSetup] {
      override def writes(o: EBSSetup): JsValue = {
        Json.obj(
          "image" -> Json.toJson(o.dockerImage),
          "config" -> Json.toJson(ISODateTimeFormat.dateTime.print(o.config.timestamp))
        )
      }
    }

    val DesiredTimelineStateJsonFormat = new Writes[DesiredTimelineState] {
      override def writes(o: DesiredTimelineState): JsValue = {
        Json.obj()
          .deepMerge(o.curEnvironment.map(e => Json.obj("current" -> Json.toJson(e))).getOrElse(Json.obj()))
          .deepMerge(o.nextEnvironment.map(e => Json.obj("next" -> Json.toJson(e))).getOrElse(Json.obj()))
          .deepMerge(o.futureEnvironment.map(e => Json.obj("future" -> Json.toJson(e))).getOrElse(Json.obj()))
      }
    }

    val TimelineConfigurationJsonFormat = new Writes[TimelineConfiguration] {
      override def writes(o: TimelineConfiguration): JsValue = {
        Json.toJson(o).as[JsObject].deepMerge(Json.obj("timestamp" -> ISODateTimeFormat.dateTime.print(o.timestamp)))
      }
    }

    Process.constant {
      in =>
        {
          (in._1, in._2) match {
            case (Some(input: DesiredStateInput), Some(desState: DesiredSystemState)) => {

              val (cmdPrefix, cmdData) = {
                input.cmd match {
                  case c: NewRollout => ("NewRollout", Json.toJson(c.release.config)(TimelineConfigurationJsonFormat).some)
                  case d: DropEnvironment => ("DropEnvironment", None)
                  case _ => ("", None)
                }
              }

              val cmdString = s"$cmdPrefix ${input.cmd.version.tag} ${input.cmd.configTimestamp.fold("")(c => s"[config: $c]")}"

              val (msg, data, usr) = e match {
                case CommandEvent => (s"[INFO] $cmdString", cmdData, input.cmd.user)
                case CommandIgnoredEvent => {
                  val desiredTimelineState = desState.timelines.get(input.name)
                  (s"[WARN] Ignored: $cmdString, ${commandIgnoredReason(input, desiredTimelineState)}", desiredTimelineState.map(Json.toJson(_)(DesiredTimelineStateJsonFormat)), None)
                }
                case _ => ("", None, None)
              }
              logEvent(Event(e, input.name, Some(input.cmd.version), msg, data, user = usr))
            }
            case (_, _) => Task.now(())
          }
        }
    }
  }

  def compareAndSetState(i: DesiredStateInput): Task[(Option[DesiredStateInput], Option[DesiredSystemState])] = {
    desiredStateSignal.compareAndSet(selectDesiredState(i)).map((i.some, _))
  }

  val desiredState: Process[Task, (Option[DesiredStateInput], Option[DesiredSystemState])] = (commandQueue.dequeue zip configurationSignal.continuous)
    .map(in => DesiredStateInput(in._1._1, in._1._2, enabled(in._1._1)))
    .filter(_.enabled)
    .logDebug(logger, s => s"Sending ${s.cmd} command to timeline ${s.name}")
    .through(channel.lift(compareAndSetState))
    .observe(logCommandEvent(CommandEvent))

  val server: Process[Task, Unit] =
    (desiredStateSignal.continuous.once.map(s => (None, s.some)) ++ desiredState)
      .pipe(process1.sliding(2))
      .filter(i => i.size > 1 && i(0)._2 == i(1)._2)
      .map(_(1))
      .to(logCommandEvent(CommandIgnoredEvent))

  val saveDesiredState: Process[Task, PutItemOutcome] = {
    def diffDesiredState(oldDesiredState: DesiredSystemState, newDesiredState: DesiredSystemState): Seq[(TimelineName, DesiredTimelineState)] = {
      for {
        (k, v) <- newDesiredState.timelines.toSeq
        if oldDesiredState.timelines.get(k).fold(true)(_ != v)
      } yield (k, v)
    }

    val saveChangedDesiredState: Channel[Task, (TimelineName, DesiredTimelineState), Throwable \/ PutItemOutcome] = {
      def saveState(name: TimelineName, state: DesiredTimelineState): Task[PutItemOutcome] = {
        for {
          table <- Dynamo.stateTable(None)
          outcome <- Dynamo.saveState(table, name, state)
        } yield outcome
      }

      Process.constant {
        case (name, state) => saveState(name, state).attempt
      }
    }

    desiredStateSignal.discrete
      .pipe(emitChanges(diffDesiredState))
      .through(saveChangedDesiredState)
      .logThrowable(logger, "Exception thrown while saving changed desired state")
  }

  def createNewRelease(release: Release): EBSSetup = {
    EBSSetup(
      DockerImage(release.config.dockerImage, release.tag),
      RuntimeConfiguration(release.config.ionrollerRoleArn, release.config.portMappings, release.config.runArgs, release.config.volumeMappings, release.config.timestamp), release.config.ebsConfig
    )
  }

  def selectDesiredTimelineState(cmd: RolloutCommand, state: DesiredTimelineState): DesiredTimelineState = {
    val newTimelineState = cmd match {
      case NewRollout(release, user) =>
        state.addNewVersion(createNewRelease(release))

      case DropEnvironment(releaseVersion, configTimestamp, force, user) =>
        state.dropVersion(releaseVersion, configTimestamp, force)
    }

    logger.debug(s"New desired state: $newTimelineState")
    newTimelineState
  }

  def selectDesiredState(in: DesiredStateInput)(state: Option[DesiredSystemState]): Option[DesiredSystemState] = {
    val oldDesiredTimelineState = state.flatMap(_.timelines.get(in.name)).getOrElse(DesiredTimelineState(Seq.empty, None))
    val newDesired = (in.cmd, state) match {
      case (cmd, Some(s)) =>
        val newState = selectDesiredTimelineState(in.cmd, oldDesiredTimelineState)
        s.copy(s.timelines.updated(in.name, newState)).some
      case (cmd, None) =>
        val newState = selectDesiredTimelineState(in.cmd, oldDesiredTimelineState)
        DesiredSystemState(Map(in.name -> newState)).some
    }
    newDesired
  }

}

object CommandManager {
  def apply(configurationSignal: Signal[SystemConfiguration], initialState: DesiredSystemState): CommandManager = new CommandManager(configurationSignal, initialState)

  def getSavedDesiredSystemState: Task[DesiredSystemState] = {
    for {
      table <- Dynamo.stateTable(None)
      state <- Dynamo.getSystemState(table)
    } yield state
  }
}
