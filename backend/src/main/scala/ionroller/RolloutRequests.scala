package ionroller

import org.joda.time.DateTime

// Do these make any sense in an REST API? Perhaps, we may want
// to translate between them.

sealed trait RolloutCommand {
  def version: ReleaseVersion

  def configTimestamp: Option[DateTime]

  def user: Option[String]
}

final case class NewRollout(release: Release, user: Option[String]) extends RolloutCommand {
  def version = release.tag

  def configTimestamp = Some(release.config.timestamp)
}

final case class DropEnvironment(version: ReleaseVersion, configTimestamp: Option[DateTime], force: Boolean, user: Option[String]) extends RolloutCommand

