package ionroller.cmd

import com.gilt.ionroller.api.v1.models.Service

final case class ServiceState(service: Option[Service], currentState: Option[String])
