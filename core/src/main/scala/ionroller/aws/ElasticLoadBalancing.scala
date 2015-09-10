package ionroller.aws

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model._

import scala.collection.JavaConverters._
import scalaz.Kleisli
import scalaz.concurrent.Task

object ElasticLoadBalancing {
  def client(credentialsProvider: AWSCredentialsProvider) = Task(new AmazonElasticLoadBalancingClient(credentialsProvider))(awsExecutorService)

  def getLoadBalancer(lb: String): Kleisli[Task, AWSClientCache, Option[LoadBalancerDescription]] = {
    Kleisli { cache =>
      val req = new DescribeLoadBalancersRequest().withLoadBalancerNames(lb)
      Task(cache.elb.describeLoadBalancers(req).getLoadBalancerDescriptions.asScala.headOption)(awsExecutorService)
    }
  }

  def registerInstances(lb: String, instances: Seq[String]): Kleisli[Task, AWSClientCache, Seq[String]] = {
    Kleisli { cache =>
      val req = new RegisterInstancesWithLoadBalancerRequest().withLoadBalancerName(lb).withInstances(instances.map(new Instance(_)): _*)
      Task(cache.elb.registerInstancesWithLoadBalancer(req).getInstances.asScala.map(_.getInstanceId))(awsExecutorService)
    }
  }

  def deregisterInstances(lb: String, instances: Seq[String]): Kleisli[Task, AWSClientCache, Seq[String]] = {
    Kleisli { cache =>
      val req = new DeregisterInstancesFromLoadBalancerRequest().withLoadBalancerName(lb).withInstances(instances.map(new Instance(_)): _*)
      Task(cache.elb.deregisterInstancesFromLoadBalancer(req).getInstances.asScala.map(_.getInstanceId))(awsExecutorService)
    }
  }

  def describeInstanceHealth(lb: String): Kleisli[Task, AWSClientCache, Seq[InstanceState]] = {
    Kleisli { cache =>
      Task {
        val req = new DescribeInstanceHealthRequest().withLoadBalancerName(lb)
        cache.elb.describeInstanceHealth(req).getInstanceStates.asScala.toSeq
      }(awsExecutorService)
    }
  }
}
