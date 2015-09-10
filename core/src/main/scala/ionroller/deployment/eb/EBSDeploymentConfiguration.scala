package ionroller.deployment.eb

import com.amazonaws.services.elasticbeanstalk.model._
import com.amazonaws.services.s3.AmazonS3
import ionroller._
import ionroller.deployment.eb.v1.{DockerAWSImageConfiguration, DockerAWSAuthenticationConfiguration, DockerrunAWSJson, Dockerfile}
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import play.api.libs.json.{JsObject, Json}

import scala.util.Random
import scalaz.Kleisli
import scalaz.concurrent.Task

final case class EBSDeploymentConfiguration(
    application: CreateApplicationVersionRequest,
    environment: CreateEnvironmentRequest,
    dockerrun: DockerrunAWSJson,
    dockerfile: Dockerfile,
    envCustomSettings: Map[String, JsObject]
) {
}

final case class DescriptionData(timeline: TimelineName, image: DockerImage, configTimestamp: DateTime, unusedSince: Option[DateTime], elbHealthChecks: Option[Boolean], rolloutStatus: RolloutStatus) {
  def toDescription: String = {
    val tInc = rolloutStatus match {
      case RolloutComplete(at) => None
      case RolloutNotStarted => None
      case RolloutStep(i) => Some(i)
      case RolloutStepComplete(i, at) => Some(i)
    }

    val tAt = rolloutStatus match {
      case RolloutComplete(at) => Some(at)
      case RolloutNotStarted => None
      case RolloutStep(i) => None
      case RolloutStepComplete(i, at) => Some(at)
    }

    val basicDescription = s"timeline=${timeline.name};image=${image.toString};config=${ISODateTimeFormat.dateTime.print(configTimestamp)};"

    val withUnused =
      unusedSince.fold(basicDescription)(d => s"${basicDescription}unused=${ISODateTimeFormat.dateTime.print(d)};")

    val withHealthCheck = elbHealthChecks.fold(withUnused)(h => s"${withUnused}elb=${h};")

    val withTInc = tInc.fold(withHealthCheck)(tInc => s"${withHealthCheck}tInc=${tInc};")

    tAt.fold(withTInc)(at => s"${withTInc}tAt=${at};")
  }
}

object EBSDeploymentConfiguration {

  def apply(timeline: TimelineName, domain: String, externalELB: Option[ExternalElbSettings], ebsSetup: EBSSetup): Kleisli[Task, AmazonS3, EBSDeploymentConfiguration] = {
    Kleisli { client =>
      val ebsConfig = ebsSetup.ebsConfiguration
      val deploymentConfig = ebsSetup.config
      val dockerImage = ebsSetup.dockerImage

      val application = {
        val sourceBundleLocation = new S3Location()
          .withS3Bucket(ebsConfig.deploymentBucket)
          .withS3Key(s"${timeline.name}-${dockerImage.repository.name}-${dockerImage.tag.tag}-${deploymentConfig.timestamp.getMillis}.zip")

        new CreateApplicationVersionRequest()
          .withApplicationName(timeline.name)
          .withVersionLabel(s"${dockerImage.repository.name}-${dockerImage.tag.tag}-${deploymentConfig.timestamp.getMillis}")
          .withAutoCreateApplication(true)
          .withSourceBundle(sourceBundleLocation)
      }
      val environment = {
        import scala.collection.JavaConverters._

        val envName = (timeline.name.take(10) + "-" + dockerImage.tag.tag)
          .replaceAll("[^0-9a-zA-Z\\-]", "-")
          .replaceFirst("^[\\-]*", "")
          .take(19) + "-" + Random.alphanumeric.take(3).mkString

        val descriptionData = DescriptionData(timeline, dockerImage, deploymentConfig.timestamp, None, Some(false), RolloutNotStarted)

        val isoDate = deploymentConfig.timestamp

        val tags = Seq(
          new Tag().withKey("ionroller:docker_image").withValue(dockerImage.toString),
          new Tag().withKey("ionroller:service").withValue(timeline.name),
          new Tag().withKey("ionroller:domain").withValue(domain),
          new Tag().withKey("ionroller:config_timestamp").withValue(isoDate.toString)
        )

        val envRequest = new CreateEnvironmentRequest()
          .withApplicationName(timeline.name)
          .withVersionLabel(s"${dockerImage.repository.name}-${dockerImage.tag.tag}-${deploymentConfig.timestamp.getMillis}")
          .withEnvironmentName(envName)
          .withDescription(descriptionData.toDescription)
          .withOptionSettings(ebsConfig.envOptionSettings.asJavaCollection)
          .withSolutionStackName(ebsConfig.solutionStack)
          .withTags(tags: _*)
          .withCNAMEPrefix(envName)

        envRequest
      }

      val dockerrun = {
        val dockercfg = ".dockercfg"

        val authConfig = for {
          metadata <- Task.delay(client.getObjectMetadata(ebsConfig.deploymentBucket, dockercfg))
        } yield DockerAWSAuthenticationConfiguration(ebsConfig.deploymentBucket, dockercfg)

        authConfig.attempt map { c =>
          DockerrunAWSJson(
            authentication = c.toOption,
            image = DockerAWSImageConfiguration(dockerImage.toString),
            ports = deploymentConfig.portMappings,
            volumes = ebsSetup.config.volumeMappings
          )
        }
      }

      val dockerfile = Dockerfile(ebsSetup.dockerImage, deploymentConfig.portMappings, deploymentConfig.runArgs)

      val mergedResources =
        externalELB match {
          case None => ebsConfig.resources
          case Some(elb) =>
            Some(ebsConfig.resources.getOrElse(JsObject(Seq.empty)) deepMerge Json.obj(
              "AWSEBSecurityGroup" -> Json.obj(
                "Type" -> "AWS::EC2::SecurityGroup",
                "Properties" -> Json.obj(
                  "SecurityGroupIngress" -> Json.arr(
                    Json.obj(
                      "IpProtocol" -> "tcp",
                      "ToPort" -> Json.obj("Ref" -> "InstancePort"),
                      "SourceSecurityGroupId" -> Json.obj("Ref" -> "AWSEBLoadBalancerSecurityGroup"),
                      "FromPort" -> Json.obj("Ref" -> "InstancePort")
                    ),
                    Json.obj(
                      "IpProtocol" -> "tcp",
                      "ToPort" -> Json.obj("Ref" -> "InstancePort"),
                      "SourceSecurityGroupId" -> elb.securityGroup,
                      "FromPort" -> Json.obj("Ref" -> "InstancePort")
                    )
                  )
                )
              )
            ))
        }

      val envCustomSettings = Map(
        "Resources" -> mergedResources,
        "packages" -> ebsConfig.packages,
        "sources" -> ebsConfig.sources,
        "files" -> ebsConfig.files,
        "users" -> ebsConfig.users,
        "groups" -> ebsConfig.groups,
        "commands" -> ebsConfig.commands,
        "container_commands" -> ebsConfig.containerCommands,
        "services" -> ebsConfig.services
      ).collect {
          case (key, Some(value)) => key -> value
        }

      dockerrun.map(r => EBSDeploymentConfiguration(application, environment, r, dockerfile, envCustomSettings))
    }
  }
}
