package ionroller.aws

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription
import com.amazonaws.services.route53.model._
import com.amazonaws.services.route53.{AmazonRoute53, AmazonRoute53Client}

import scala.collection.JavaConverters._
import scalaz.Kleisli
import scalaz.concurrent.Task
import scalaz.std.option._
import scalaz.syntax.std.option._

object Route53 {
  val client: Kleisli[Task, AWSCredentialsProvider, AmazonRoute53] = {
    Kleisli { credentialsProvider =>
      Task(new AmazonRoute53Client(credentialsProvider))(awsExecutorService)
    }
  }

  def getDnsNameFromRRS(rrs: ResourceRecordSet): Option[String] = {
    for {
      aliasTarget <- Option(rrs.getAliasTarget)
      dnsName <- Option(aliasTarget.getDNSName)
    } yield dnsName
  }

  private[aws] def getChangeReq(rrSet: ResourceRecordSet, hostedZoneId: String) = {
    val change = new Change().withAction(ChangeAction.UPSERT).withResourceRecordSet(rrSet)
    val changeBatch = new ChangeBatch().withChanges(change)
    new ChangeResourceRecordSetsRequest().withChangeBatch(changeBatch).withHostedZoneId(hostedZoneId)
  }

  def setAliasRecordForTarget(name: String, hostedZoneId: String, targetName: String, targetZoneId: String): Kleisli[Task, AWSClientCache, ChangeInfo] = {
    Kleisli { client =>
      val aliasTarget: AliasTarget = new AliasTarget().withDNSName(targetName).withEvaluateTargetHealth(true).withHostedZoneId(targetZoneId)
      val rrSetWithoutWeight = new ResourceRecordSet().withName(name).withAliasTarget(aliasTarget).withType(RRType.A)
      val rrSetWithWeight = new ResourceRecordSet().withName(name).withAliasTarget(aliasTarget).withType(RRType.A).withSetIdentifier("ionroller").withWeight(0L)

      Task {
        client.route53.changeResourceRecordSets(getChangeReq(rrSetWithWeight, hostedZoneId))
      }(awsExecutorService).handleWith {
        case e: com.amazonaws.services.route53.model.InvalidChangeBatchException if e.getErrorMessage.contains("non-weighted set exists with the same name and type") =>
          Task.delay {
            client.route53.changeResourceRecordSets(getChangeReq(rrSetWithoutWeight, hostedZoneId))
          }
      } map (_.getChangeInfo)
    }
  }

  def setARecord(name: String, hostedZoneId: String, targetIP: String): Kleisli[Task, AWSClientCache, ChangeInfo] = {
    Kleisli { client =>
      val rr = new ResourceRecord().withValue(targetIP)
      val rrSetWithoutWeight = new ResourceRecordSet().withName(name).withResourceRecords(rr).withTTL(60L).withType(RRType.A)
      val rrSetWithWeight = new ResourceRecordSet().withName(name).withResourceRecords(rr).withTTL(60L).withType(RRType.A).withSetIdentifier("ionroller").withWeight(0L)

      Task {
        client.route53.changeResourceRecordSets(getChangeReq(rrSetWithWeight, hostedZoneId))
      }(awsExecutorService) handleWith {
        case e: com.amazonaws.services.route53.model.InvalidChangeBatchException if e.getErrorMessage.contains("non-weighted set exists with the same name and type") =>
          Task.delay {
            client.route53.changeResourceRecordSets(getChangeReq(rrSetWithoutWeight, hostedZoneId))
          }
      } map (_.getChangeInfo)
    }
  }

  def setAliasRecordForLoadBalancer(name: String, hostedZoneId: String, lb: Option[LoadBalancerDescription]): Kleisli[Task, AWSClientCache, Option[ChangeInfo]] = {
    Kleisli { client =>
      val optionTask = for {
        l <- lb
      } yield setAliasRecordForTarget(name, hostedZoneId, l.getDNSName, l.getCanonicalHostedZoneNameID).run(client)

      optionTask.fold(Task.now(none[ChangeInfo]))(_.map(_.some))
    }
  }

  // TODO: This uses multiple AWS clients, so should go elsewhere
  // TODO: Also, use a Kleisli instead of parameters
  def setAliasRecordForEnv(creds: AWSClientCache, envCreds: AWSClientCache, name: String, hostedZoneId: String, environmentID: String): Task[Option[ChangeInfo]] = {
    for {
      lb <- ElasticBeanstalk.getLoadBalancerFromEnvId(environmentID).run(envCreds)
      setARecord <- setAliasRecordForLoadBalancer(name, hostedZoneId, lb).run(creds)
    } yield setARecord
  }

  def setARecordForIP(name: String, hostedZoneId: String, ip: String): Kleisli[Task, AWSClientCache, ChangeInfo] = {
    for {
      setARecord <- setARecord(name, hostedZoneId, ip)
    } yield setARecord
  }

  def listResourceRecordSets(hostedZoneId: String): Kleisli[Task, AWSClientCache, List[ResourceRecordSet]] = {
    Kleisli { client =>
      def go(rrsSoFar: List[ResourceRecordSet], nextRecordName: Option[String], nextRecordType: Option[String], nextRecordIdentifier: Option[String]): Task[List[ResourceRecordSet]] = Task.delay {
        val req = new ListResourceRecordSetsRequest().withHostedZoneId(hostedZoneId)
        nextRecordIdentifier foreach req.setStartRecordIdentifier
        nextRecordName foreach req.setStartRecordName
        nextRecordType foreach req.setStartRecordType

        val response = client.route53.listResourceRecordSets(req)

        (rrsSoFar ::: response.getResourceRecordSets.asScala.toList, response.getIsTruncated.booleanValue, Option(response.getNextRecordName), Option(response.getNextRecordType), Option(response.getNextRecordIdentifier))
      } flatMap {
        case (rrs, true, nextName, nextType, nextIdentifier) =>
          go(rrs, nextName, nextType, nextIdentifier)
        case (rrs, _, _, _, _) =>
          Task.now(rrs)
      }

      Task.fork(go(List.empty, None, None, None))(awsExecutorService)
    }
  }
}
