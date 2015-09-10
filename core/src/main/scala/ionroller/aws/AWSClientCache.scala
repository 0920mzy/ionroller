package ionroller.aws

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalk
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing
import com.amazonaws.services.route53.AmazonRoute53
import com.amazonaws.services.s3.AmazonS3

import scalaz.concurrent.Task
import scalaz.{Kleisli, Nondeterminism}

class AWSClientCache(
  val role: String,
  val credentialsProvider: AWSCredentialsProvider,
  val route53: AmazonRoute53,
  val elasticBeanstalk: AWSElasticBeanstalk,
  val s3: AmazonS3,
  val asg: AmazonAutoScaling,
  val elb: AmazonElasticLoadBalancing
)

object AWSClientCache {
  private[ionroller] val cache: java.util.concurrent.ConcurrentHashMap[String, AWSClientCache] = new java.util.concurrent.ConcurrentHashMap

  val getCache: Kleisli[Task, String, AWSClientCache] = {
    Kleisli { role =>
      Option(cache.get(role)) match {
        case None =>
          for {
            credentials <- CredentialsProvider(role)
            route53Client = Route53.client(credentials)
            elasticBeanstalkClient = ElasticBeanstalk.client(credentials)
            s3Client = S3.client(credentials)
            asgClient = AutoScaling.client(credentials)
            elbClient = ElasticLoadBalancing.client(credentials)
            newItem <- Nondeterminism[Task].apply5(route53Client, elasticBeanstalkClient, s3Client, asgClient, elbClient) {
              case (r53, eb, s3, asg, elb) =>
                val newEntry = new AWSClientCache(role, credentials, r53, eb, s3, asg, elb)
                cache.put(role, newEntry)
                newEntry
            }
          } yield newItem

        case Some(e) => Task.now(e)
      }
    }
  }
}
