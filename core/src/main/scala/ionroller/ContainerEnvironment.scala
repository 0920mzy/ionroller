package ionroller

import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.elasticbeanstalk.model.{ApplicationVersionDescription, EnvironmentDescription, EventDescription}
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

import scalaz._
import scalaz.std.option._
import scalaz.syntax.either._
import scalaz.syntax.std.option._

final case class ContainerEnvironment(timeline: TimelineName, setup: EBSSetup, created: EnvironmentCreatedDetails, environmentDescription: EnvironmentDescription, healthy: Boolean, unused: Option[EnvironmentUnusedDetails], events: Seq[EventDescription], asg: Option[AutoScalingGroup], elbChanged: Option[Boolean], rolloutStatus: RolloutStatus) {
  def curEndpoint: Option[String \/ String] = {
    val beforeLowerCase = for {
      desc <- Option(environmentDescription.getEndpointURL)
    } yield desc

    val lowerCase = beforeLowerCase.map(_.toLowerCase)

    if (beforeLowerCase == lowerCase) {
      lowerCase.map(_.left)
    } else {
      lowerCase.map(_ + ".").map(_.right)
    }
  }
}

object ContainerEnvironment {
  def patternFor(tag: String) =
    s".*${tag}=([^;]*).*".r

  val ConfigPattern = patternFor("config")
  val UnusedPattern = patternFor("unused")
  val TimelinePattern = patternFor("timeline")
  val DockerImagePattern = patternFor("image")
  val ElbHealthChecks = patternFor("elb")
  val TrafficInc = patternFor("tInc")
  val TrafficCompletedAt = patternFor("tAt")

  def createEbsSetup(role: String, env: EnvironmentDescription, dockerImage: DockerImage, app: ApplicationVersionDescription): EBSSetup = {
    //val settings = configSettings.find { setting =>
    //  env.getEnvironmentName == setting.getEnvironmentName
    //}
    val solutionStackName = env.getSolutionStackName
    //val optionSettings = settings.fold(Seq.empty[ConfigurationOptionSetting])(_.getOptionSettings.asScala.toSeq)
    //TODO port mappings from Dockerrun.json

    val configTimestamp = env.getDescription match {
      case ConfigPattern(config) => ISODateTimeFormat.dateTimeParser.parseDateTime(config)
      case _ => new DateTime //TODO read latest config signal here
    }

    val runtimeConfig = RuntimeConfiguration(role, Seq.empty, Seq.empty, Seq.empty, configTimestamp)
    val ebsConfig = EBSConfiguration(app.getSourceBundle.getS3Bucket, solutionStackName, Seq.empty, None, None, None, None, None, None, None, None, None)

    EBSSetup(dockerImage, runtimeConfig, ebsConfig)
  }

  def containerEnvironment(role: String, appVersionDescriptions: Seq[ApplicationVersionDescription], env: EnvironmentDescription, events: Seq[EventDescription], asg: Option[AutoScalingGroup]): Option[ContainerEnvironment] = {

    val dockerImage = env.getDescription match {
      case DockerImagePattern(imageString) => DockerImage(imageString).some
      case _ => none
    }

    val configTimestamp = env.getDescription match {
      case ConfigPattern(imageString) => ISODateTimeFormat.dateTimeParser().parseDateTime(imageString).getMillis
      case _ => (new DateTime).getMillis //we should not reach this code
    }

    // We cannot read env.getApplicationVersionLabel here as for launching envs it will be empty
    val app = appVersionDescriptions.find {
      case desc =>
        desc.getApplicationName == env.getApplicationName &&
          dockerImage.fold(false)(i => desc.getVersionLabel == s"${i.repository.name}-${i.tag.tag}-$configTimestamp")
    }.orElse {
      //TODO this could be removed in the future
      appVersionDescriptions.find {
        case desc =>
          desc.getApplicationName == env.getApplicationName &&
            dockerImage.fold(false)(i => desc.getVersionLabel == s"${i.repository.name}-${i.tag.tag}")
      }
    }

    (dockerImage, app) match {
      case (Some(i), Some(a)) =>
        ContainerEnvironment.fromEnvironmentDescription(env, createEbsSetup(role, env, i, a), events, asg)
      case _ => none
    }
  }

  def fromEnvironmentDescription(env: EnvironmentDescription, ebsSetup: EBSSetup, events: Seq[EventDescription], asg: Option[AutoScalingGroup]): Option[ContainerEnvironment] = {
    val created = EnvironmentCreatedDetails(new DateTime(env.getDateCreated), env.getEnvironmentId)

    val timeline = env.getDescription match {
      case TimelinePattern(t) => TimelineName(t)
      case _ => {
        TimelineName(env.getApplicationName)
      }
    }

    val unusedSince = env.getDescription match {
      case UnusedPattern(unused) => ISODateTimeFormat.dateTimeParser.parseDateTime(unused).some
      case _ => None
    }

    val elbHealthDone = env.getDescription match {
      case ElbHealthChecks("true") => Some(true)
      case ElbHealthChecks("false") => Some(false)
      case _ => None
    }

    val trafficInc = env.getDescription match {
      case TrafficInc(i) => Some(i.toInt)
      case _ => None
    }

    val trafficCompletedAt = env.getDescription match {
      case TrafficCompletedAt(at) => ISODateTimeFormat.dateTimeParser.parseDateTime(at).some
      case _ => None
    }

    val curRolloutStatus: RolloutStatus = {
      (trafficInc, trafficCompletedAt) match {
        case (None, None) => RolloutNotStarted
        case (Some(i), None) => RolloutStep(i)
        case (None, Some(at)) => RolloutComplete(at)
        case (Some(i), Some(at)) => RolloutStepComplete(i, at)
      }
    }

    //TODO red envs?
    // TODO: also fill in rest of AutoScalingData information
    (env.getStatus, env.getHealth) match {
      case ("Terminating", _) => none
      case ("Terminated", _) => none
      case (_, "Green") => ContainerEnvironment(timeline, ebsSetup, created, env, true, unusedSince.map(EnvironmentUnusedDetails), events, asg, elbHealthDone, curRolloutStatus).some
      case (_, _) => ContainerEnvironment(timeline, ebsSetup, created, env, false, unusedSince.map(EnvironmentUnusedDetails), events, asg, elbHealthDone, curRolloutStatus).some
    }
  }

}
