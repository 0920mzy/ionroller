package controllers

import com.amazonaws.services.elasticbeanstalk.model.ConfigurationOptionSetting
import com.gilt.ionroller.api.v0.models
import ionroller._
import org.joda.time.DateTime

import scala.concurrent.duration._
import scalaz.Functor
import scalaz.std.list._
import scalaz.std.option._

trait ApidocAdapter[A, B] {
  def fromApidoc(a: A): B

  def toApidoc(b: B): A
}

trait LowImplicits {

  implicit class ApidocAdapterWrapper[A](a: A) {
    def fromApidoc[B](implicit apidocAdapter: ApidocAdapter[A, B]): B = {
      apidocAdapter.fromApidoc(a)
    }

    def toApidoc[B](implicit apidocAdapter: ApidocAdapter[B, A]): B = {
      apidocAdapter.toApidoc(a)
    }
  }

}

object ApidocImplicits extends LowImplicits {
  implicit class ApidocAdapterFunctorWrapper[A, F[A]](fa: F[A])(implicit functor: Functor[F]) {
    def fromApidoc[B](implicit apidocAdapter: ApidocAdapter[A, B]): F[B] = {
      functor.map(fa)(apidocAdapter.fromApidoc)
    }

    def toApidoc[B](implicit apidocAdapter: ApidocAdapter[B, A]): F[B] = {
      functor.map(fa)(apidocAdapter.toApidoc)
    }
  }

  implicit object PortMappingAdapter extends ApidocAdapter[models.PortMapping, ionroller.PortMapping] {
    def fromApidoc(m: models.PortMapping): ionroller.PortMapping = {
      ionroller.PortMapping(m.internal, m.external)
    }

    def toApidoc(i: ionroller.PortMapping): models.PortMapping = {
      models.PortMapping(i.containerPort, i.hostPort)
    }
  }

  implicit object VolumeMappingAdapter extends ApidocAdapter[models.VolumeMapping, ionroller.VolumeMapping] {
    def fromApidoc(m: models.VolumeMapping): ionroller.VolumeMapping = {
      ionroller.VolumeMapping(m.internal, m.external)
    }

    def toApidoc(i: ionroller.VolumeMapping): models.VolumeMapping = {
      models.VolumeMapping(i.containerPath, i.hostIdentifier)
    }
  }

  implicit object DockerRepositoryAdapter extends ApidocAdapter[String, ionroller.DockerRepository] {
    def fromApidoc(m: String): ionroller.DockerRepository = {
      ionroller.DockerRepository(m)
    }

    def toApidoc(repo: ionroller.DockerRepository): String = {
      repo.toString
    }
  }

  implicit object RolloutStatusAdapter extends ApidocAdapter[models.RolloutStatus, ionroller.RolloutStatus] {
    def fromApidoc(m: models.RolloutStatus): ionroller.RolloutStatus = {
      (m.numInstances, m.completedAt) match {
        case (None, None) => RolloutNotStarted
        case (Some(i), None) => RolloutStep(i)
        case (None, Some(at)) => RolloutComplete(at)
        case (Some(i), Some(at)) => RolloutStepComplete(i, at)
      }
    }

    def toApidoc(s: ionroller.RolloutStatus): models.RolloutStatus = {
      s match {
        case RolloutNotStarted => models.RolloutStatus(None, None)
        case RolloutStep(i) => models.RolloutStatus(Some(i), None)
        case RolloutStepComplete(i, at) => models.RolloutStatus(Some(i), Some(at))
        case RolloutComplete(at) => models.RolloutStatus(None, Some(at))
      }
    }
  }

  implicit object DockerImageAdapter extends ApidocAdapter[models.Release, (ionroller.DockerImage, DateTime)] {
    def fromApidoc(m: models.Release): (ionroller.DockerImage, DateTime) = {
      (ionroller.DockerImage(DockerRepository(m.image), ReleaseVersion(m.tag)), m.config)
    }

    def toApidoc(in: (ionroller.DockerImage, DateTime)): models.Release = {
      models.Release(in._1.repository.toString, in._1.tag.tag, in._2)
    }
  }

  implicit object OptionSettingAdapter extends ApidocAdapter[models.OptionSetting, ConfigurationOptionSetting] {
    def fromApidoc(m: models.OptionSetting): ConfigurationOptionSetting = {
      new ConfigurationOptionSetting(m.Namespace, m.OptionName, m.Value)
    }

    def toApidoc(s: ConfigurationOptionSetting): models.OptionSetting = {
      models.OptionSetting(s.getNamespace, s.getOptionName, s.getValue)
    }
  }

  implicit object ElbSettingsAdapter extends ApidocAdapter[models.ElbSettings, ionroller.ExternalElbSettings] {
    def fromApidoc(m: models.ElbSettings): ionroller.ExternalElbSettings = {
      ionroller.ExternalElbSettings(m.name, m.securityGroup, Duration(m.rolloutDelayMinutes, "minutes"))
    }

    def toApidoc(s: ionroller.ExternalElbSettings): models.ElbSettings = {
      models.ElbSettings(s.name, s.securityGroup, s.rolloutRate.toMinutes.toInt)
    }
  }

  implicit object EBSConfigurationAdapter extends ApidocAdapter[models.EbConfig, ionroller.EBSConfiguration] {
    def fromApidoc(m: models.EbConfig): ionroller.EBSConfiguration = {
      ionroller.EBSConfiguration(
        m.deploymentBucket,
        m.stack.getOrElse(ConfigurationManager.defaultSolutionStack),
        m.settings.toList.fromApidoc,
        m.resources,
        m.packages,
        m.sources,
        m.files,
        m.users,
        m.groups,
        m.commands,
        m.containerCommands,
        m.services
      )
    }

    def toApidoc(c: ionroller.EBSConfiguration): models.EbConfig = {
      models.EbConfig(c.deploymentBucket, Some(c.solutionStack), c.envOptionSettings.toList.toApidoc, c.resources, c.packages, c.sources, c.files, c.users, c.groups, c.commands, c.containerCommands, c.services)
    }
  }

  implicit object ConfigurationAdapter extends ApidocAdapter[models.ServiceConfig, ionroller.TimelineConfiguration] {
    def fromApidoc(m: models.ServiceConfig): ionroller.TimelineConfiguration = {
      ionroller.TimelineConfiguration(
        m.url,
        m.hostedZoneId,
        m.externalElb.fromApidoc,
        DockerRepository(m.image),
        m.awsAccountId,
        m.serviceRole,
        m.portMappings.toList.fromApidoc,
        m.volumeMappings.getOrElse(List.empty).toList.fromApidoc,
        m.runArgs,
        m.removeUnusedAfterMinutes.map(new FiniteDuration(_, MINUTES)),
        m.eb.fromApidoc,
        new DateTime
      )
    }

    def toApidoc(c: ionroller.TimelineConfiguration): models.ServiceConfig = {
      models.ServiceConfig(
        c.url,
        c.hostedZoneId,
        c.awsAccountId,
        c.externalElb.toApidoc,
        c.serviceRole,
        c.dockerImage.toString,
        c.portMappings.toList.toApidoc,
        Some(c.volumeMappings.toList.toApidoc),
        c.runArgs,
        c.ebsConfig.toApidoc,
        c.removeUnusedAfter.map(_.toMinutes),
        Some(c.timestamp)
      )
    }
  }

  // TODO: check correctness of this conversion
  implicit object DesiredStateAdapter extends ApidocAdapter[models.ServiceDesiredState, ionroller.DesiredTimelineState] {
    def fromApidoc(m: models.ServiceDesiredState): ionroller.DesiredTimelineState = {
      ???
    }

    def toApidoc(d: ionroller.DesiredTimelineState): models.ServiceDesiredState = {
      models.ServiceDesiredState(
        d.curEnvironment.map(env => (env.dockerImage, env.config.timestamp)).toApidoc,
        d.nextEnvironment.map(env => (env.dockerImage, env.config.timestamp)).toApidoc,
        d.futureEnvironment.map(env => (env.dockerImage, env.config.timestamp)).toApidoc
      )
    }
  }

}
