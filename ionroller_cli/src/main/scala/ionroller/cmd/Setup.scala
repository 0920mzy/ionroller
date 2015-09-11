package ionroller.cmd

import com.amazonaws.auth.policy.{Principal, Policy, Statement}
import com.amazonaws.auth.policy.actions.{SecurityTokenServiceActions, DynamoDBv2Actions}
import com.amazonaws.services.dynamodbv2.document.Table
import com.amazonaws.services.identitymanagement.{AmazonIdentityManagementClient, AmazonIdentityManagement}
import com.amazonaws.services.identitymanagement.model._
import com.gilt.ionroller.api.v0.models.ServiceConfig
import ionroller.{ApidocImplicits, TimelineConfiguration}
import ionroller.aws.Dynamo
import scala.collection.JavaConverters._
import org.joda.time.DateTime

import scalaz.Kleisli
import scalaz.concurrent.Task

object Setup {
  def setupOrGetConfigTable(curTables: Set[String]): Task[Table] = {
    if (!curTables.contains(Dynamo.defaultConfigTable)) {
      println("Setting up config table.")
      Dynamo.createConfigTable(None)
    } else {
      println("Config table exists.")
      Dynamo.configTable(None)
    }
  }

  def setupEventTable(curTables: Set[String]): Task[Option[Table]] = {
    if (!curTables.contains(Dynamo.defaultEventTable)) {
      println("Setting up events table.")
      Dynamo.createEventsTable(None).map(Option.apply)
    } else {
      println("Events table exists.")
      Task.now(None)
    }
  }

  def setupStateTable(curTables: Set[String]): Task[Option[Table]] = {
    if (!curTables.contains(Dynamo.defaultStateTable)) {
      println("Setting up state table.")
      Dynamo.createStateTable(None).map(Option.apply)
    } else {
      println("State table exists.")
      Task.now(None)
    }
  }

  def getRole(roleName: String): Kleisli[Task, AmazonIdentityManagement, Role] = {
    Kleisli { client =>
      val req = new GetRoleRequest().withRoleName(roleName)
      Task.delay(client.getRole(req).getRole) map { role =>
        println(s"""Role "$roleName" exists.""")
        role
      }
    }
  }

  def getExtraPolicyForService(policyName: String): Kleisli[Task, AmazonIdentityManagement, Option[com.amazonaws.services.identitymanagement.model.Policy]] = {
    Kleisli { client =>
      val req = new ListPoliciesRequest()
      Task.delay(client.listPolicies(req).getPolicies.asScala.find(_.getPolicyName == policyName))
    }
  }

  def createExtraPolicyForService(policyName: String, accountId: Long, tables: List[String]): Kleisli[Task, AmazonIdentityManagement, com.amazonaws.services.identitymanagement.model.Policy] = {
    Kleisli { client: AmazonIdentityManagement =>
      val dynamoResources = tables.map(t => new com.amazonaws.auth.policy.Resource(s"arn:aws:dynamodb:us-east-1:$accountId:table/$t"))
      val dynamoStatement = new Statement(Statement.Effect.Allow).withActions(DynamoDBv2Actions.AllDynamoDBv2Actions).withResources(dynamoResources: _*)

      val policy = new Policy().withStatements(dynamoStatement)
      val req = new CreatePolicyRequest().withPolicyName(policyName).withPolicyDocument(policy.toJson)

      Task.delay(client.createPolicy(req).getPolicy)
    }
  }

  def getOrCreateExtraPolicyForService(policyName: String, accountId: Long, tables: List[String]): Kleisli[Task, AmazonIdentityManagement, com.amazonaws.services.identitymanagement.model.Policy] = {
    getExtraPolicyForService(policyName) flatMap {
      case None => createExtraPolicyForService(policyName, accountId, tables)
      case Some(policy) => Kleisli { _ => Task.now(policy) }
    }
  }

  def addPermissionsToRole(roleName: String, extraPolicy: String): Kleisli[Task, AmazonIdentityManagement, Unit] = {
    import scalaz.std.list._
    import scalaz.syntax.traverse._

    Kleisli { client =>
      println(s"Adding access policies to role $roleName")

      val policies = List(
        "arn:aws:iam::aws:policy/AWSElasticBeanstalkFullAccess",
        "arn:aws:iam::aws:policy/AmazonRoute53FullAccess",
        "arn:aws:iam::aws:policy/AmazonEC2ContainerServiceFullAccess",
        extraPolicy
      )

      policies.traverse { role =>
        val policy = new AttachRolePolicyRequest().withRoleName(roleName).withPolicyArn(role)
        Task.delay(client.attachRolePolicy(policy))
      } map { _ => () }
    }
  }

  def createRole(roleName: String, assumeRolePolicyDocument: String): Kleisli[Task, AmazonIdentityManagement, Role] = {
    Kleisli { client =>
      println(s"""Creating role "$roleName".""")
      val req = new CreateRoleRequest().withRoleName(roleName).withAssumeRolePolicyDocument(assumeRolePolicyDocument)
      Task.delay {
        client.createRole(req).getRole
      }
    }
  }

  def accessPolicyDocument(userArn: String): String = {
    val rootStatement = new Statement(Statement.Effect.Allow).withPrincipals(new Principal(userArn)).withActions(SecurityTokenServiceActions.AssumeRole)
    val ec2Statement = new Statement(Statement.Effect.Allow).withPrincipals(new Principal(Principal.Services.AmazonEC2)).withActions(SecurityTokenServiceActions.AssumeRole)
    val policy = new Policy().withStatements(rootStatement, ec2Statement)
    policy.toJson
  }

  val getUserArn: Kleisli[Task, AmazonIdentityManagement, String] = {
    Kleisli { client =>
      Task.delay(client.getUser.getUser.getArn)
    }
  }

  def getOrCreateIonrollerRole(roleName: String, userArn: String): Kleisli[Task, AmazonIdentityManagement, Role] = {
    Kleisli { client =>
      getRole(roleName)(client).handleWith {
        case e: NoSuchEntityException => createRole(roleName, accessPolicyDocument(userArn))(client)
      }
    }
  }

  def maybeAddRoleToInstanceProfile(profile: InstanceProfile, role: Role): Kleisli[Task, AmazonIdentityManagement, Unit] = {
    import scala.collection.JavaConverters._

    Kleisli { client =>
      if (profile.getRoles.asScala.exists(_.getRoleId == role.getRoleId))
        Task.now(())
      else {
        println("""Linking instance profile and role together.""")
        val req = new AddRoleToInstanceProfileRequest().withInstanceProfileName(profile.getInstanceProfileName).withRoleName(role.getRoleName)
        Task.delay(client.addRoleToInstanceProfile(req))
      }
    }
  }

  def getInstanceProfile(profileName: String): Kleisli[Task, AmazonIdentityManagement, InstanceProfile] = {
    Kleisli { client =>
      val req = new GetInstanceProfileRequest().withInstanceProfileName(profileName)
      Task.delay(client.getInstanceProfile(req).getInstanceProfile) map { role =>
        println(s"""Instance profile "$profileName" exists.""")
        role
      }
    }
  }

  def createInstanceProfile(profileName: String): Kleisli[Task, AmazonIdentityManagement, InstanceProfile] = {
    Kleisli { client =>
      println(s"""Creating instance profile "$profileName".""")
      val req = new CreateInstanceProfileRequest().withInstanceProfileName(profileName)
      Task.delay(client.createInstanceProfile(req).getInstanceProfile)
    }
  }

  def getOrCreateInstanceProfile(profileName: String): Kleisli[Task, AmazonIdentityManagement, InstanceProfile] = {
    Kleisli { client =>
      getInstanceProfile(profileName)(client) handleWith {
        case e: NoSuchEntityException => createInstanceProfile(profileName)(client)
      }
    }
  }

  def serviceConfigToTimelineConfig(serviceConfig: ServiceConfig): Task[TimelineConfiguration] = {
    import ApidocImplicits._

    Task.delay(serviceConfig.fromApidoc)
  }

  def addConfigToDynamoDB(config: ServiceConfig) = {
    for {
      table <- Dynamo.configTable(None)
      timelineConfig <- serviceConfigToTimelineConfig(config)
      _ = { println("Saving config in table: " + timelineConfig) }
      save <- Dynamo.saveConfig(table, "ionroller", timelineConfig)
    } yield save
  }

  def setup(config: ServiceConfig) = for {
    tables <- Dynamo.listTables
    configTable <- setupOrGetConfigTable(tables)
    eventTable <- setupEventTable(tables)
    stateTable <- setupStateTable(tables)
    identityClient <- Task.delay(new AmazonIdentityManagementClient())
    userArn <- getUserArn(identityClient)
    accountId <- Task.delay(userArn.split(":")(4).toLong)
    role <- getOrCreateIonrollerRole("ionroller", userArn).run(identityClient)
    extraPolicy <- getOrCreateExtraPolicyForService("IONRollerExtraPolicy", accountId, List(Dynamo.defaultConfigTable, Dynamo.defaultEventTable, Dynamo.defaultStateTable))(identityClient)
    _ <- addPermissionsToRole("ionroller", extraPolicy.getArn)(identityClient)
    instanceProfile <- getOrCreateInstanceProfile("ionroller")(identityClient)
    _ <- maybeAddRoleToInstanceProfile(instanceProfile, role)(identityClient)
    _ <- addConfigToDynamoDB(config)
  } yield ()

}