package ionroller.tracking

import java.net.InetAddress
import java.util.Date

import com.typesafe.scalalogging.StrictLogging
import ionroller.{ReleaseVersion, TimelineName}

import scalaz.\/
import scalaz.concurrent.Task

sealed trait EventType extends Product with Serializable

case object NewDesiredState extends EventType

case object DeploymentStarted extends EventType

case object EnvironmentCreated extends EventType

case object ElbHealthCheckAdded extends EventType

case object TrafficMoved extends EventType

case object AttachedELB extends EventType

case object DetachedELB extends EventType

case object ReleaseRequestIgnored extends EventType

case object RemovalListUpdated extends EventType

case object EnvironmentRemovalRequested extends EventType

case object EnvironmentMarkedUnused extends EventType

case object EnvironmentMarkedUsed extends EventType

case object EnvironmentRemoved extends EventType

case object EnvironmentAdded extends EventType

case object EnvironmentHealthChanged extends EventType

case object EnvironmentStatusChanged extends EventType

case object EnvironmentEvent extends EventType

case object ExceptionEvent extends EventType

case object ServiceEvent extends EventType

case object ConfigurationChanged extends EventType

case object EnvironmentNotHealthy extends EventType

case object IncrementTrafficRequested extends EventType

case object DecrementTrafficRequested extends EventType

case object WaitingForTrafficDecrement extends EventType

case object WaitingForTrafficIncrement extends EventType

case object WaitingForNextRolloutStep extends EventType

case object FinishingTrafficRollout extends EventType

case object CommandIgnoredEvent extends EventType

case object CommandEvent extends EventType

final case class Event(
  eventType: EventType,
  service: TimelineName,
  version: Option[ReleaseVersion],
  message: String,
  data: Option[Object],
  timestamp: Long = new Date().getTime * 1000,
  host: String = Event.host,
  user: Option[String] = None
)

object Event {
  val host = InetAddress.getLocalHost.getHostName
}

trait EventLogger extends StrictLogging {
  def log(event: Event): Task[Throwable \/ Unit]
}
