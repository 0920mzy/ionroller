package ionroller

import org.joda.time.DateTime

final case class EnvironmentToRemove(version: String, config: Option[DateTime], force: Boolean)
