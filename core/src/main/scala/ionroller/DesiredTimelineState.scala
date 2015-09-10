package ionroller

import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

final case class DesiredTimelineState(environmentsToRemove: Seq[EnvironmentToRemove], environments: Option[(EBSSetup, Option[(EBSSetup, Option[EBSSetup])])]) {
  def curEnvironment = environments.map(_._1)

  def nextEnvironment = environments.flatMap(_._2.map(_._1))

  def futureEnvironment = environments.flatMap(_._2.flatMap(_._2))

  def addNewVersion(setup: EBSSetup) = {
    val newEnvs = environments match {
      case None => Some((setup, None))
      case Some((current, None)) if !versionExistsInEnvs(setup.dockerImage.tag, setup.config.timestamp, environments) => Some((current, Some((setup, None))))
      case Some((current, Some((next, _)))) if !versionExistsInEnvs(setup.dockerImage.tag, setup.config.timestamp, environments) => Some((current, Some((next, Some(setup)))))
      case _ => environments // already exists!
    }

    copy(environments = newEnvs)
  }

  def versionExistsInEnvs(v: ReleaseVersion, timestamp: DateTime, envs: Option[(EBSSetup, Option[(EBSSetup, Option[EBSSetup])])]): Boolean = {
    envs.map(_._1).exists(s => s.dockerImage.tag == v && timestamp == s.config.timestamp) ||
      envs.flatMap(_._2.map(_._1)).exists(s => s.dockerImage.tag == v && timestamp == s.config.timestamp) ||
      envs.flatMap(_._2.flatMap(_._2)).exists(s => s.dockerImage.tag == v && timestamp == s.config.timestamp)
  }

  def updatedToDrop(newEnvs: Option[(EBSSetup, Option[(EBSSetup, Option[EBSSetup])])], toDrop: Seq[EnvironmentToRemove], releaseVersion: ReleaseVersion, config: Option[DateTime], force: Boolean) = {

    def verExists = newEnvs.map(_._1).exists(s => s.dockerImage.tag == releaseVersion && config.fold(true)(_ == s.config.timestamp)) ||
      newEnvs.flatMap(_._2.map(_._1)).exists(s => s.dockerImage.tag == releaseVersion && config.fold(true)(_ == s.config.timestamp)) ||
      newEnvs.flatMap(_._2.flatMap(_._2)).exists(s => s.dockerImage.tag == releaseVersion && config.fold(true)(_ == s.config.timestamp))

    if (verExists)
      toDrop.toSet.toList
    else
      (toDrop ++ Seq(EnvironmentToRemove(releaseVersion.tag, config, force))).toSet.toList
  }

  def dropVersion(releaseVersion: ReleaseVersion, config: Option[DateTime], force: Boolean): DesiredTimelineState = {
    this match {
      case DesiredTimelineState(toDrop, Some((cur, Some((next, Some(fut)))))) if fut.dockerImage.tag == releaseVersion && config.fold(true)(_ == fut.config.timestamp) =>
        val newEnvs = Some((cur, Some((next, None))))
        val newToDrop = updatedToDrop(newEnvs, toDrop, releaseVersion, config, force)
        config match {
          case Some(c) => DesiredTimelineState(newToDrop, newEnvs)
          case None => DesiredTimelineState(newToDrop, newEnvs).dropVersion(releaseVersion, config, force)
        }
      case DesiredTimelineState(toDrop, Some((cur, Some((next, optFut))))) if next.dockerImage.tag == releaseVersion && config.fold(true)(_ == next.config.timestamp) =>
        val newEnvs = Some((cur, optFut.map(f => (f, None))))
        val newToDrop = updatedToDrop(newEnvs, toDrop, releaseVersion, config, force)
        config match {
          case Some(c) => DesiredTimelineState(newToDrop, newEnvs)
          case None => DesiredTimelineState(newToDrop, newEnvs).dropVersion(releaseVersion, config, force)
        }
      case DesiredTimelineState(toDrop, Some((cur, optNext))) if cur.dockerImage.tag == releaseVersion && config.fold(true)(_ == cur.config.timestamp) && force =>
        val newEnvs = optNext.map(n => (n._1, n._2.map(f => (f, None))))
        val newToDrop = updatedToDrop(newEnvs, toDrop, releaseVersion, config, force)
        DesiredTimelineState(newToDrop, newEnvs)
      case DesiredTimelineState(toDrop, envs) =>
        val newToDrop = updatedToDrop(envs, toDrop, releaseVersion, config, force)
        DesiredTimelineState(newToDrop, envs)
      case other => other
    }
  }

  def startNextDeployment = {
    nextEnvironment match {
      case None => this
      case Some(next) => DesiredTimelineState(this.environmentsToRemove, Some((next, futureEnvironment.map((_, None)))))
    }
  }
}

object DesiredTimelineState {
  implicit lazy val jsonFormat = {
    def applyDesiredState(envsToRemove: Seq[EnvironmentToRemove], cur: Option[EBSSetup], next: Option[EBSSetup], future: Option[EBSSetup]): DesiredTimelineState = {
      (cur, next, future) match {
        case (Some(c), Some(n), Some(f)) => DesiredTimelineState(envsToRemove, Some((c, Some((n, Some(f))))))
        case (Some(c), Some(n), _) => DesiredTimelineState(envsToRemove, Some((c, Some((n, None)))))
        case (Some(c), _, _) => DesiredTimelineState(envsToRemove, Some((c, None)))
        case (None, _, _) => DesiredTimelineState(envsToRemove, None)
      }
    }
    implicit lazy val envToRemoveJsonFormat = Json.format[EnvironmentToRemove]

    def unapplyDesiredState(s: DesiredTimelineState): (Seq[EnvironmentToRemove], Option[EBSSetup], Option[EBSSetup], Option[EBSSetup]) = {
      (
        s.environmentsToRemove,
        s.curEnvironment,
        s.nextEnvironment,
        s.futureEnvironment
      )
    }
    (

      (JsPath \ "environmentsToRemove").formatNullable[Seq[EnvironmentToRemove]].inmap(
        (f: Option[Seq[EnvironmentToRemove]]) => f.getOrElse(Seq.empty),
        (g: Seq[EnvironmentToRemove]) => Some(g)
      ) and
        (JsPath \ "curEnvironment").formatNullable[EBSSetup] and
        (JsPath \ "next").formatNullable[EBSSetup] and
        (JsPath \ "future").formatNullable[EBSSetup]
    )(applyDesiredState _, unapplyDesiredState _)
  }
}
