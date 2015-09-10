package ionroller.aws

import java.util.Date

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.elasticbeanstalk.model._
import com.amazonaws.services.elasticbeanstalk.{AWSElasticBeanstalk, AWSElasticBeanstalkClient}
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription
import com.typesafe.scalalogging.StrictLogging
import org.joda.time.DateTime

import scala.collection.JavaConverters._
import scalaz.Kleisli
import scalaz.concurrent.Task
import scalaz.std.option._

object ElasticBeanstalk extends StrictLogging {
  val client: Kleisli[Task, AWSCredentialsProvider, AWSElasticBeanstalk] = {
    Kleisli { credentialsProvider =>
      Task(new AWSElasticBeanstalkClient(credentialsProvider))(awsExecutorService)
    }
  }

  def createApplicationVersion(app: CreateApplicationVersionRequest): Kleisli[Task, AWSClientCache, ApplicationVersionDescription] = {
    Kleisli { client =>
      Task(client.elasticBeanstalk.createApplicationVersion(app).getApplicationVersion)(awsExecutorService)
    }
  }

  def describeApplicationVersions(applicationName: String, versionLabel: String): Kleisli[Task, AWSClientCache, Seq[ApplicationVersionDescription]] = {
    Kleisli { client =>
      val describeApplicationVersionsRequest = new DescribeApplicationVersionsRequest().withApplicationName(applicationName).withVersionLabels(versionLabel)
      Task(client.elasticBeanstalk.describeApplicationVersions(describeApplicationVersionsRequest).getApplicationVersions.asScala)(awsExecutorService)
    }
  }

  def describeConfigurationSettings(applicationName: String, environmentName: String): Kleisli[Task, AWSClientCache, Seq[ConfigurationSettingsDescription]] = {
    Kleisli { client =>
      val request = new DescribeConfigurationSettingsRequest().withApplicationName(applicationName).withEnvironmentName(environmentName)
      Task(client.elasticBeanstalk.describeConfigurationSettings(request).getConfigurationSettings.asScala)(awsExecutorService)
    }
  }

  val describeApplicationVersions: Kleisli[Task, AWSClientCache, Seq[ApplicationVersionDescription]] = {
    Kleisli { client =>
      Task(client.elasticBeanstalk.describeApplicationVersions().getApplicationVersions.asScala)(awsExecutorService)
    }
  }

  val describeEnvironments: Kleisli[Task, AWSClientCache, Seq[EnvironmentDescription]] = {
    Kleisli { client =>
      Task(client.elasticBeanstalk.describeEnvironments().getEnvironments.asScala)(awsExecutorService)
    }
  }

  def describeEnvironmentResources(id: String): Kleisli[Task, AWSClientCache, EnvironmentResourceDescription] = {
    Kleisli { client =>
      Task(client.elasticBeanstalk.describeEnvironmentResources(new DescribeEnvironmentResourcesRequest().withEnvironmentId(id)).getEnvironmentResources)(awsExecutorService)
    }
  }

  def updateEnvironmentDescription(envName: String, description: String): Kleisli[Task, AWSClientCache, UpdateEnvironmentResult] = {
    Kleisli { client =>
      val req = new UpdateEnvironmentRequest().withEnvironmentName(envName).withDescription(description)
      Task(client.elasticBeanstalk.updateEnvironment(req))(awsExecutorService)
    }
  }

  def describeEvents(since: Option[DateTime]): Kleisli[Task, AWSClientCache, Seq[EventDescription]] = {
    Kleisli { client =>
      def go(eventsSoFar: List[EventDescription], token: Option[String]): Task[List[EventDescription]] = Task.delay {
        val req = new DescribeEventsRequest().withSeverity(EventSeverity.TRACE)
        since foreach { d => req.setStartTime(new Date(d.getMillis)) }
        token foreach { t => req.setNextToken(t) }
        val description = client.elasticBeanstalk.describeEvents(req)
        (eventsSoFar ::: description.getEvents.asScala.toList, Option(description.getNextToken))
      } flatMap {
        case (events, t @ Some(newToken)) if t != token =>
          logger.debug(s"Needed multiple describeEvents calls, token=$token newToken=$t")
          go(events, t)
        case (events, _) =>
          Task.now(events)
      }

      Task.fork(go(List.empty, None))(awsExecutorService)
    }
  }

  def terminateEnvironment(envName: String): Kleisli[Task, AWSClientCache, TerminateEnvironmentResult] = {
    Kleisli { client =>
      val req = new TerminateEnvironmentRequest().withEnvironmentName(envName).withTerminateResources(true)
      Task(client.elasticBeanstalk.terminateEnvironment(req))(awsExecutorService)
    }
  }

  def deleteApplicationVersion(appName: String, versionLabel: String): Kleisli[Task, AWSClientCache, Unit] = {
    Kleisli { client =>
      val req = new DeleteApplicationVersionRequest().withApplicationName(appName).withDeleteSourceBundle(true).withVersionLabel(versionLabel)
      Task(client.elasticBeanstalk.deleteApplicationVersion(req))(awsExecutorService)
    }
  }

  def getEnvironmentResources(environnmentId: String): Kleisli[Task, AWSClientCache, Option[EnvironmentResourceDescription]] = {
    Kleisli { client =>
      Task(
        Option(client.elasticBeanstalk.describeEnvironmentResources(new DescribeEnvironmentResourcesRequest().withEnvironmentId(environnmentId)))
          .map(_.getEnvironmentResources)
      )(awsExecutorService)
    }
  }

  def getLoadBalancerFromEnv(envResources: EnvironmentResourceDescription): Kleisli[Task, AWSClientCache, Option[LoadBalancerDescription]] = {
    Kleisli { cache =>
      envResources.getLoadBalancers.asScala.headOption match {
        case Some(lb) => ElasticLoadBalancing.getLoadBalancer(lb.getName).run(cache)
        case None => Task.now(None)
      }
    }
  }

  // AWSClientCache dependency code should be factored out from more specific dependencies
  def getLoadBalancerFromEnvId(environmentID: String): Kleisli[Task, AWSClientCache, Option[LoadBalancerDescription]] = {
    Kleisli { cache =>
      for {
        envResources <- getEnvironmentResources(environmentID).run(cache)
        lb <- envResources.fold(Task.now(none[LoadBalancerDescription]))(getLoadBalancerFromEnv(_).run(cache))
      } yield lb
    }
  }

  def getAutoScalingGroups(envId: String): Kleisli[Task, AWSClientCache, Seq[String]] = {
    for {
      resources <- ElasticBeanstalk.describeEnvironmentResources(envId)
    } yield resources.getAutoScalingGroups.asScala.map(_.getName)
  }
}