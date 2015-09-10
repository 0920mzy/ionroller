package ionroller.deployment.eb

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.{ZipEntry, ZipOutputStream}

import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalk
import com.amazonaws.services.elasticbeanstalk.model._
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{ObjectMetadata, PutObjectResult}
import com.amazonaws.util.IOUtils
import com.typesafe.scalalogging.LazyLogging
import ionroller.aws.{AWSClientCache, ElasticBeanstalk}
import ionroller.deployment.eb.v1.{DockerrunAWSJson, Dockerfile}
import ionroller.{EBSSetup, ExternalElbSettings, TimelineName}
import play.api.libs.json.{JsObject, Json}

import scala.collection.JavaConverters._
import scala.io.Source
import scalaz.Kleisli
import scalaz.concurrent.Task
import scalaz.std.option._

final case class DeploymentResult(applicationVersion: String, environment: CreateEnvironmentResult)

class EBSDeployer(timeline: TimelineName, domain: String, externalElb: Option[ExternalElbSettings], deploymentConfig: EBSSetup) extends LazyLogging {

  val deploy: Kleisli[Task, AWSClientCache, DeploymentResult] = {
    Kleisli { cache =>
      logger.debug(s"Deploying ${timeline.name} ${deploymentConfig.dockerImage}")
      for {
        config <- EBSDeploymentConfiguration(timeline, domain, externalElb, deploymentConfig).run(cache.s3)
        sourceBundle <- createSourceBundle(config).run(cache.s3)
        ver <- createApplicationVersion(config).run(cache)
        env <- createEnvironment(config).run(cache.elasticBeanstalk)
      } yield DeploymentResult(config.application.getApplicationName, env)
    }
  }

  def maybeCreateApplication(app: CreateApplicationVersionRequest, curAppVersion: Option[ApplicationVersionDescription]): Kleisli[Task, AWSClientCache, ApplicationVersionDescription] = {
    curAppVersion match {
      case None => ElasticBeanstalk.createApplicationVersion(app)
      case Some(a) => Kleisli { _ => Task.now(a) }
    }
  }

  def createApplicationVersion(config: EBSDeploymentConfiguration): Kleisli[Task, AWSClientCache, ApplicationVersionDescription] = {
    val app = config.application

    for {
      appVersionDescriptions <- ElasticBeanstalk.describeApplicationVersions(app.getApplicationName, app.getVersionLabel).mapK(_.attempt.map(_.fold(t => none, d => d.headOption)))
      newAppVersionDescription <- maybeCreateApplication(app, appVersionDescriptions)
    } yield newAppVersionDescription
  }

  def createEnvironment(config: EBSDeploymentConfiguration): Kleisli[Task, AWSElasticBeanstalk, CreateEnvironmentResult] = {
    Kleisli { client =>
      Task.delay(client.createEnvironment(config.environment))
    }
  }

  def createSourceBundle(config: EBSDeploymentConfiguration): Kleisli[Task, AmazonS3, PutObjectResult] = {
    Kleisli { client =>
      val key = config.application.getSourceBundle.getS3Key
      val bucket = config.application.getSourceBundle.getS3Bucket

      def setupMetadata(bos: ByteArrayOutputStream): Task[ObjectMetadata] = {
        Task.delay {
          val metadata = new ObjectMetadata
          metadata.setContentType("application/zip")
          // we need to call new ByteArrayInputStream again, as checking the length reads the stream
          val contentBytes = IOUtils.toByteArray(new ByteArrayInputStream(bos.toByteArray)).length.toLong
          metadata.setContentLength(contentBytes)
          metadata
        }
      }

      def createZip(bos: ByteArrayOutputStream, dockerfile: Dockerfile, dockerrun: DockerrunAWSJson, customEnvSettings: Map[String, JsObject]) = {
        val zos = new ZipOutputStream(bos)
        Task.delay {
          zos.putNextEntry(new ZipEntry(dockerfile.getName))
          zos.write(dockerfile.toString.getBytes)
          zos.closeEntry
          zos.putNextEntry(new ZipEntry(dockerrun.getName))
          zos.write(Json.toJson(dockerrun).toString.getBytes)
          zos.closeEntry
          zos.putNextEntry(new ZipEntry(".ebextensions/01custom.config"))
          zos.write(play.api.libs.json.Json.toJson(customEnvSettings).toString().getBytes)
          zos.closeEntry

        } onFinish { _ => Task.delay(zos.close()) }
      }

      val bos = new ByteArrayOutputStream()

      for {
        _ <- createZip(bos, config.dockerfile, config.dockerrun, config.envCustomSettings)
        metadata <- setupMetadata(bos)
        inputStream = new ByteArrayInputStream(bos.toByteArray)
      } yield client.putObject(bucket, key, inputStream, metadata)
    }
  }

  def getSettings(config: EBSDeploymentConfiguration, env: CreateEnvironmentResult): Kleisli[Task, AWSClientCache, Seq[ConfigurationOptionSetting]] = {
    def getOptionSettings(configs: Seq[ConfigurationSettingsDescription]): Seq[ConfigurationOptionSetting] = {
      val optionSettings = for {
        desc <- configs.headOption.toSeq
        optionSetting <- desc.getOptionSettings.asScala
      } yield optionSetting

      optionSettings
    }

    val configurationSettings = ElasticBeanstalk.describeConfigurationSettings(config.application.getApplicationName, env.getEnvironmentName)
    val settings = configurationSettings.map(getOptionSettings)

    val configSetting = config.environment.getOptionSettings.asScala.toSeq
    settings.map(_.intersect(configSetting))
  }
}

object EBSDeployer {
  def apply(timeline: TimelineName, domain: String, externalELB: Option[ExternalElbSettings], config: EBSSetup) = {
    new EBSDeployer(timeline, domain, externalELB, config)
  }
}