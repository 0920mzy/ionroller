package ionroller

import com.amazonaws.services.autoscaling.model.Instance
import com.amazonaws.services.elasticloadbalancing.model.InstanceState

final case class LiveInfoForSetup(env: Option[ContainerEnvironment], registeredInstances: Seq[(Instance, InstanceState)], unregisteredInstances: Seq[Instance])
