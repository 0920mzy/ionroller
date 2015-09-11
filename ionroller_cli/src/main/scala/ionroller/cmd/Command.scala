package ionroller.cmd

import org.joda.time.DateTime

sealed trait Command extends Product with Serializable

final case class CmdRelease(service: String, version: String, configTimestamp: Option[DateTime], emergency: Boolean) extends Command

final case class CmdDrop(service: String, version: String, configTimestamp: Option[DateTime], force: Boolean) extends Command

final case class CmdConfig(service: String, timestamp: Option[DateTime]) extends Command

final case class CmdSetConfig(service: String, file: Option[String]) extends Command

final case class CmdDeleteConfig(service: String) extends Command

final case class CmdConfigs(service: String, from: Option[DateTime], to: Option[DateTime]) extends Command

final case class CmdEvents(service: String, version: Option[String], from: Option[DateTime], to: Option[DateTime]) extends Command

final case class CmdCurrent(service: String) extends Command

case object CmdVersion extends Command

case object CmdUpdate extends Command

final case class CmdSetup(file: String) extends Command

final case class CmdSetBaseUrl(baseUrl: String) extends Command

final case class CmdSetCliUpdateUrl(updateUrl: String) extends Command
