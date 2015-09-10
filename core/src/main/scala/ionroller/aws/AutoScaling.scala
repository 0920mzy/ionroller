package ionroller.aws

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.autoscaling.model._
import com.amazonaws.services.autoscaling.{AmazonAutoScaling, AmazonAutoScalingClient}
import com.amazonaws.services.elasticloadbalancing.model.InstanceState
import com.typesafe.scalalogging.StrictLogging

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration
import scalaz._
import scalaz.concurrent.Task

object AutoScaling extends StrictLogging {
  val client: Kleisli[Task, AWSCredentialsProvider, AmazonAutoScaling] = {
    Kleisli { credentialsProvider =>
      Task(new AmazonAutoScalingClient(credentialsProvider))(awsExecutorService)
    }
  }

  def getAutoScalingGroupDetails(asgs: Seq[String]): Kleisli[Task, AWSClientCache, List[AutoScalingGroup]] = {
    Kleisli { client =>

      def go(asgsSoFar: List[AutoScalingGroup], token: Option[String]): Task[List[AutoScalingGroup]] = Task.delay {
        val req =
          if (asgs.isEmpty)
            new DescribeAutoScalingGroupsRequest()
          else
            new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(asgs: _*)

        token foreach { t => req.setNextToken(t) }
        val response = client.asg.describeAutoScalingGroups(req)
        (asgsSoFar ::: response.getAutoScalingGroups.asScala.toList, Option(response.getNextToken))
      } flatMap {
        case (events, t @ Some(newToken)) if t != token =>
          logger.debug(s"Needed multiple getAutoScalingGroups calls, token=$token newToken=$t")
          go(events, t)
        case (events, _) =>
          Task.now(events)
      }

      Task.fork(go(List.empty, None))(awsExecutorService)
    }
  }

  def getUnregisteredInstance(elbInstances: Seq[InstanceState], asg: AutoScalingGroup): Option[String] = {
    val lbInstances = elbInstances.map(_.getInstanceId).toSet
    val asgInstances = asg.getInstances.asScala
    val healthyAsgInstances = asgInstances.filter(_.getHealthStatus == "Healthy").map(_.getInstanceId).toSet

    (healthyAsgInstances -- lbInstances).toSeq.sorted.headOption
  }

  def attachElb(asg: AutoScalingGroup, lb: String): Kleisli[Task, AWSClientCache, AttachLoadBalancersResult] = {
    Kleisli { cache =>
      val attachRequest = new AttachLoadBalancersRequest().withAutoScalingGroupName(asg.getAutoScalingGroupName).withLoadBalancerNames(lb)
      Task(cache.asg.attachLoadBalancers(attachRequest))(awsExecutorService)
    }
  }

  def detachElb(asg: AutoScalingGroup, lb: String): Kleisli[Task, AWSClientCache, DetachLoadBalancersResult] = {
    Kleisli { cache =>
      val detachRequest = new DetachLoadBalancersRequest().withAutoScalingGroupName(asg.getAutoScalingGroupName).withLoadBalancerNames(lb)
      Task(cache.asg.detachLoadBalancers(detachRequest))(awsExecutorService)
    }
  }

  def updateElbHealthCheck(asgs: Seq[String], healthCheckType: String, gracePeriod: FiniteDuration): Kleisli[Task, AWSClientCache, Unit] = {
    Kleisli { cache =>

      for {
        _ <- Task.gatherUnordered(
          asgs map { name =>
            Task(
              cache.asg.updateAutoScalingGroup(
                new UpdateAutoScalingGroupRequest()
                  .withAutoScalingGroupName(name)
                  .withHealthCheckType(healthCheckType)
                  .withHealthCheckGracePeriod(gracePeriod.toSeconds.toInt)
              )
            )(awsExecutorService)
          }
        )
      } yield ()
    }
  }
}
