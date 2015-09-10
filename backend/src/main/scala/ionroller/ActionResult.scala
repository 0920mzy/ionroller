package ionroller

import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import org.joda.time.DateTime

sealed trait ActionResultOrNext extends Product with Serializable

case object TryNextAction extends ActionResultOrNext

sealed trait ActionResult extends ActionResultOrNext

final case class WaitingForEnvironment(envDetails: EBSSetup) extends ActionResult

final case class CreateEnvironment(envDetails: EBSSetup) extends ActionResult

final case class UpdateElbHealthCheck(c: ContainerEnvironment) extends ActionResult

final case class MoveDNS(c: ContainerEnvironment) extends ActionResult

final case class MarkEnvironmentUsed(c: ContainerEnvironment) extends ActionResult

final case class MarkEnvironmentUnused(c: ContainerEnvironment) extends ActionResult

case object StartNextDeployment extends ActionResult

final case class RemoveEnvironment(c: ContainerEnvironment) extends ActionResult

final case class UpdateRemovalRequests(versions: Seq[EnvironmentToRemove]) extends ActionResult

final case class StartRolloutStep(c: ContainerEnvironment, step: Int) extends ActionResult

final case class FinishRolloutStep(c: ContainerEnvironment, step: Int, at: DateTime) extends ActionResult

final case class WaitingForNextRolloutStep(c: ContainerEnvironment, at: DateTime) extends ActionResult

final case class FinishRollout(c: ContainerEnvironment, at: DateTime) extends ActionResult

final case class AttachElb(c: ContainerEnvironment, asg: AutoScalingGroup, lb: String) extends ActionResult

final case class DetachElb(c: ContainerEnvironment, asg: AutoScalingGroup, lb: String) extends ActionResult

final case class IncrementTraffic(c: ContainerEnvironment, instanceIds: Seq[String], to: Option[Int]) extends ActionResult

final case class DecrementTraffic(c: ContainerEnvironment, instanceIds: Seq[String], to: Option[Int]) extends ActionResult

final case class WaitingForTrafficIncrement(c: ContainerEnvironment) extends ActionResult

final case class WaitingForTrafficDecrement(c: ContainerEnvironment) extends ActionResult
