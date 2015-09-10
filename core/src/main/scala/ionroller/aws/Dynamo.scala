package ionroller.aws

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import com.amazonaws.services.dynamodbv2.document._
import com.amazonaws.services.dynamodbv2.document.spec.{QuerySpec, ScanSpec}
import com.amazonaws.services.dynamodbv2.model._
import ionroller.tracking._
import ionroller.{JsonUtil, TimelineConfiguration, TimelineName}
import org.joda.time.DateTime
import play.api.libs.json._

import scala.collection.JavaConverters._
import scala.collection.immutable.HashMap
import scalaz.\/
import scalaz.concurrent.Task
import scalaz.std.list._
import scalaz.syntax.foldable._
import scalaz.syntax.std.option._

object Dynamo {
  import JsonUtil.Implicits._

  val client = new AmazonDynamoDBClient()
  implicit val db = new DynamoDB(client)

  val defaultConfigTable: String = "IonrollerConfig"
  val defaultStateTable: String = "IonrollerState"
  val defaultEventTable: String = "IonrollerEvents"

  def listTables: Task[Set[String]] = {
    def go(tablesSoFar: Set[String], exclusiveStartTableName: Option[String]): Task[Set[String]] = {
      val req = new ListTablesRequest()
      exclusiveStartTableName foreach req.setExclusiveStartTableName

      Task(client.listTables(req))(awsExecutorService) flatMap { listTablesResult =>
        val tables = listTablesResult.getTableNames.asScala.toSet ++ tablesSoFar

        Option(listTablesResult.getLastEvaluatedTableName) match {
          case None => Task.now(tables)
          case l @ Some(lastTable) => go(tables, l)
        }
      }
    }

    go(Set.empty, None)
  }

  def configTable(tableName: Option[String]): Task[Table] = {
    Task(db.getTable(tableName | defaultConfigTable))(awsExecutorService)
  }

  def stateTable(tableName: Option[String]): Task[Table] = {
    Task(db.getTable(tableName | defaultStateTable))(awsExecutorService)
  }

  def eventTable(tableName: Option[String]): Task[Table] = {
    Task(db.getTable(tableName | defaultEventTable))(awsExecutorService)
  }

  def createConfigTable(tableName: Option[String]): Task[Table] = {
    val req = new CreateTableRequest()
      .withTableName(tableName | defaultConfigTable)
      .withKeySchema(new KeySchemaElement().withKeyType(KeyType.HASH).withAttributeName("name"))
      .withAttributeDefinitions(new AttributeDefinition().withAttributeName("name").withAttributeType(ScalarAttributeType.S))
      .withKeySchema(new KeySchemaElement().withKeyType(KeyType.RANGE).withAttributeName("timestamp"))
      .withAttributeDefinitions(new AttributeDefinition().withAttributeName("timestamp").withAttributeType(ScalarAttributeType.N))
      .withProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(5L).withWriteCapacityUnits(1L))

    Task(db.createTable(req))(awsExecutorService)
  }

  def createStateTable(tableName: Option[String]): Task[Table] = {
    val req = new CreateTableRequest()
      .withTableName(tableName | defaultStateTable)
      .withKeySchema(new KeySchemaElement().withKeyType(KeyType.HASH).withAttributeName("name"))
      .withAttributeDefinitions(new AttributeDefinition().withAttributeName("name").withAttributeType(ScalarAttributeType.S))
      .withProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(1L).withWriteCapacityUnits(1L))

    Task(db.createTable(req))(awsExecutorService)
  }

  def createEventsTable(tableName: Option[String]): Task[Table] = {
    val req = new CreateTableRequest()
      .withTableName(tableName | defaultEventTable)
      .withKeySchema(new KeySchemaElement().withKeyType(KeyType.HASH).withAttributeName("name"))
      .withAttributeDefinitions(new AttributeDefinition().withAttributeName("name").withAttributeType(ScalarAttributeType.S))
      .withKeySchema(new KeySchemaElement().withKeyType(KeyType.RANGE).withAttributeName("timestamp"))
      .withAttributeDefinitions(new AttributeDefinition().withAttributeName("timestamp").withAttributeType(ScalarAttributeType.N))
      .withAttributeDefinitions(new AttributeDefinition().withAttributeName("version").withAttributeType(ScalarAttributeType.S))
      .withLocalSecondaryIndexes(new LocalSecondaryIndex()
        .withIndexName("Version-index")
        .withKeySchema(new KeySchemaElement().withKeyType(KeyType.HASH).withAttributeName("name"))
        .withKeySchema(new KeySchemaElement().withKeyType(KeyType.RANGE).withAttributeName("version"))
        .withProjection(new Projection().withProjectionType(ProjectionType.ALL)))
      .withProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(50L).withWriteCapacityUnits(1L))

    Task(db.createTable(req))(awsExecutorService)
  }

  def saveConfig(table: Table, timelineName: String, config: ionroller.TimelineConfiguration)(implicit writer: Writes[ionroller.TimelineConfiguration]): Task[PutItemOutcome] = {
    val item = new Item()
      .withString("name", timelineName)
      .withJSON("config", writer.writes(config).toString)
      .withLong("timestamp", config.timestamp.getMillis)

    Task(table.putItem(item))(awsExecutorService)
  }

  def saveState(table: Table, timelineName: TimelineName, state: ionroller.DesiredTimelineState)(implicit writer: Writes[ionroller.DesiredTimelineState]): Task[PutItemOutcome] = {
    val item = new Item()
      .withString("name", timelineName.name)
      .withJSON("state", writer.writes(state).toString)

    Task(table.putItem(item))(awsExecutorService)
  }

  def readConfigs(table: Table)(implicit reader: Reads[ionroller.TimelineConfiguration]): Task[ionroller.SystemConfiguration] = {

    def readOutcomes(outcomes: Iterable[Item]): ionroller.SystemConfiguration = {
      var configs = Map[TimelineName, ionroller.TimelineConfiguration]()

      for {
        item <- outcomes
      } {
        val name = item.getString("name")
        val timestamp = item.getLong("timestamp")
        val configJs = Json.parse(item.getJSON("config")).as[JsObject].deepMerge(Json.obj("timestamp" -> JsNumber(timestamp)))
        configs = configs.updated(TimelineName(name), configJs.as[ionroller.TimelineConfiguration])
      }
      ionroller.SystemConfiguration(configs)
    }

    readLatestConfigs(table).map(readOutcomes)
  }

  def readLatestConfigs(table: Table)(implicit reader: Reads[ionroller.TimelineConfiguration]): Task[Iterable[Item]] = {
    Task {
      for {
        service <- table.scan(new ScanSpec().withAttributesToGet("name")).asScala.toSeq.map(_.getString("name")).distinct
        config <- table.query(new QuerySpec().withHashKey("name", service).withScanIndexForward(false).withMaxResultSize(1)).asScala.toSeq
      } yield config
    }(awsExecutorService)
  }

  def getConfig(table: Table, serviceName: String, timestamp: DateTime)(implicit reader: Reads[ionroller.TimelineConfiguration]): Task[Option[TimelineConfiguration]] = {
    getConfigs(table, serviceName, timestamp.some, timestamp.some).map(_.headOption)
  }

  def getConfig(serviceName: String, timestamp: DateTime)(implicit reader: Reads[ionroller.TimelineConfiguration]): Task[Option[TimelineConfiguration]] = {
    for {
      table <- configTable(None)
      config <- getConfigs(table, serviceName, timestamp.some, timestamp.some).map(_.headOption)
    } yield config
  }

  def getConfigs(table: Table, serviceName: String, from: Option[DateTime], to: Option[DateTime]): Task[Seq[TimelineConfiguration]] = {
    Task {
      val rkCond = new RangeKeyCondition("timestamp")
      val rangeKeyCondition = (from, to) match {
        case (Some(f), Some(t)) =>
          if (f != t) rkCond.between(f.getMillis, t.getMillis).some
          else rkCond.eq(f.getMillis).some
        case (Some(f), None) => rkCond.ge(f.getMillis).some
        case (None, Some(t)) => rkCond.le(t.getMillis).some
        case (None, None) => None
      }
      val querySpec = new QuerySpec()
        .withHashKey("name", serviceName)
        .withScanIndexForward(false)
      for {
        item <- (rangeKeyCondition match {
          case Some(cond) => table.query(querySpec.withRangeKeyCondition(cond))
          case None => table.query(querySpec)
        }).asScala.toSeq
      } yield (Json.parse(item.getJSON("config")).as[JsObject] ++ Json.obj("timestamp" -> JsNumber(item.getLong("timestamp")))).as[ionroller.TimelineConfiguration]
    }(awsExecutorService)
  }

  def deleteConfig(table: Table, serviceName: String): Task[Unit] = {
    Task {
      table.query(new QuerySpec()
        .withHashKey("name", serviceName)
        .withScanIndexForward(false)).asScala.toSeq foreach { item =>
        {
          println(item)
          table.deleteItem("name", serviceName, "timestamp", item.getLong("timestamp"))
        }
      }
    }(awsExecutorService)
  }

  def readStates(table: Table)(implicit reader: Reads[ionroller.TimelineConfiguration]): Task[Map[TimelineName, ionroller.DesiredTimelineState]] = {
    import scala.collection.JavaConverters._

    def readOutcomes(outcomes: Iterable[Item]): Map[TimelineName, ionroller.DesiredTimelineState] = {
      var configs = Map[TimelineName, ionroller.DesiredTimelineState]()

      for {
        item <- outcomes
      } {
        configs = configs.updated(TimelineName(item.getString("name")), Json.parse(item.getJSON("state")).as[ionroller.DesiredTimelineState])
      }

      configs
    }

    Task(table.scan())(awsExecutorService).map(o => readOutcomes(o.asScala))
  }

  def getSystemConfig(table: Table)(implicit reader: Reads[ionroller.TimelineConfiguration]): Task[ionroller.SystemConfiguration] = {
    readConfigs(table)
  }

  def getSystemState(table: Table)(implicit reader: Reads[ionroller.DesiredTimelineState]): Task[ionroller.DesiredSystemState] = {
    readStates(table).map(c => ionroller.DesiredSystemState(c))
  }

  private[aws] def getLastItemForService(table: Table, item: Item): Task[Option[Item]] = {
    val qs = new QuerySpec()
      .withHashKey("name", item.getString("name"))
      .withScanIndexForward(false)
      .withMaxResultSize(1)
    Task.delay(table.query(qs).asScala.toList.headOption)
  }

  def putItem(table: Table, item: Item) = {
    Task.delay(table.putItem(item))
  }

  def sameRecurringEventItem(currItem: Item, lastRecurringEventItem: Option[Item]): Boolean = {
    (currItem, lastRecurringEventItem) match {
      case (c, Some(l)) => {
        val type1 = c.getString("type")
        val type2 = l.getString("type")
        val s1 = c.getString("message")
        val s2 = l.getString("message")
        val t1 = c.getLong("timestamp")
        val t2 = l.getLong("timestamp")
        val tDiffMinutes = (t1 - t2) / (1000000 * 60)
        // AWS Service can return one of those messages
        val msg1 = "[ERROR] Configuration validation exception: MinSize is greater than MaxSize"
        val msg2 = "[ERROR] Configuration validation exception: MaxSize is less than MinSize"
        if (type1 == EnvironmentNotHealthy.toString && type1 != type2)
          tDiffMinutes < 5
        else (type1 == type2) && (s1 == s2 || (s1 == msg1 && s2 == msg2) || (s1 == msg2 && s2 == msg1)) && tDiffMinutes < 60
      }
      case (c, None) => false
    }
  }

  def putRecurringEventItem(table: Table, item: Item) = {
    for {
      lastRecurringEventItem <- getLastItemForService(table, item)
      _ <- {
        if (!sameRecurringEventItem(item, lastRecurringEventItem))
          putItem(table, item)
        else
          Task.now(())
      }
    } yield ()
  }

  object EventLogger extends ionroller.tracking.EventLogger {

    override def log(event: Event): Task[Throwable \/ Unit] = {
      val outcomeTask = for {
        table <- eventTable(None)
        item <- item(event)
        _ <- {
          if (event.eventType == ExceptionEvent || event.eventType == EnvironmentNotHealthy || event.eventType == WaitingForTrafficIncrement || event.eventType == WaitingForTrafficDecrement || event.eventType == WaitingForNextRolloutStep) putRecurringEventItem(table, item)
          else putItem(table, item)
        }
      } yield ()
      outcomeTask.attempt
    }
  }

  def item(event: Event): Task[Item] = {
    for {
      data <- toJson(event.data)
      item <- Task({
        val item = new Item()
          .withString("name", event.service.name)
          .withLong("timestamp", event.timestamp)
          .withString("host", event.host)
          .withString("type", event.eventType.toString)
          .withJSON("data", data)
        if (!event.message.isEmpty) item.withString("message", event.message)
        event.version.foreach(v => item.withString("version", v.tag))
        event.user.foreach(u => item.withString("user", u))
        item
      })(awsExecutorService)
    } yield item
  }

  def itemToEventJsonString(item: Item): String = {
    try {
      Json.obj(
        "type" -> item.getString("type"),
        "service" -> item.getString("name"),
        "version" -> item.getString("version"),
        "timestamp" -> item.getLong("timestamp"),
        "message" -> item.getString("message"),
        "data" -> {
          if (item.getJSON("data") != null) Json.parse(item.getJSON("data")) else ""
        },
        "host" -> item.getString("host"),
        "user" -> item.getString("user")
      ).toString()
    } catch {
      case ex: Exception => {
        ""
      }
    }
  }

  def toJson(obj: Option[Object]): Task[String] = {
    Task({
      obj match {
        case (Some(js: JsValue)) => js
        case _ => Json.parse(obj.toJson)
      }
    })(awsExecutorService).map(_.toString)
  }

  def readItems(table: Table, scanSpec: Option[ScanSpec]) = {
    scanSpec match {
      case None => Task.delay(table.scan().asScala.toList)
      case Some(spec) => Task.delay(table.scan(spec).asScala.toList)
    }
  }

  def queryTable[A](table: Table, querySpecs: Seq[QuerySpec], sortBy: Option[Item => A], maxResultSize: Option[Int])(implicit ord: Ordering[A]) = {
    val tasks = querySpecs map {
      querySpec =>
        Task(table.query(querySpec).asScala.toList)(awsExecutorService)
    }
    Task.gatherUnordered(tasks).map(_.foldMap(identity)).map(seq => {
      sortBy match {
        case Some(function) => {
          val result = seq.sortBy(function)
          maxResultSize match {
            case Some(max) => result.takeRight(max)
            case None => result
          }
        }
        case None => seq
      }
    })
  }

  def queryEvents(services: Seq[String], from: Option[Long], to: Option[Long], version: Option[String], maxResultSize: Option[Int]) = {
    val (rangeKeyCondition, filterValueMap, filterExpression) = {
      val versionFilterExpression = "attribute_not_exists(version) or (version = :version)"
      (from, to, version) match {
        case (Some(f), Some(t), None) => (Some(new RangeKeyCondition("timestamp").between(f, t)), None, None)
        case (Some(f), None, None) => (Some(new RangeKeyCondition("timestamp").gt(f)), None, None)
        case (None, Some(t), None) => (Some(new RangeKeyCondition("timestamp").lt(t)), None, None)
        case (None, None, Some(v)) => (
          None,
          Some(HashMap[String, Object](":version" -> v).asJava),
          Some(versionFilterExpression)
        )
        case (Some(f), Some(t), Some(v)) => (
          Some(new RangeKeyCondition("timestamp").between(f, t)),
          Some(HashMap[String, Object](":version" -> v).asJava),
          Some(versionFilterExpression)
        )
        case (Some(f), None, Some(v)) => (
          Some(new RangeKeyCondition("timestamp").gt(f)),
          Some(HashMap[String, Object](":version" -> v).asJava),
          Some(versionFilterExpression)
        )
        case (None, Some(t), Some(v)) => (
          Some(new RangeKeyCondition("timestamp").lt(t)),
          Some(HashMap[String, Object](":version" -> v).asJava),
          Some(versionFilterExpression)
        )
        case (_, _, _) => (None, None, None)
      }
    }
    val querySpecs = for {
      service <- services
    } yield {
      val qs = new QuerySpec().withHashKey("name", service).withScanIndexForward(false)
      (rangeKeyCondition, filterValueMap, filterExpression) match {
        case (Some(rk: RangeKeyCondition), Some(nm: java.util.Map[String, Object]), Some(fe: String)) => qs.withRangeKeyCondition(rk).withValueMap(nm).withFilterExpression(fe)
        case (Some(rk: RangeKeyCondition), None, None) => qs.withRangeKeyCondition(rk)
        case (None, Some(nm: java.util.Map[String, Object]), Some(fe: String)) => qs.withValueMap(nm).withFilterExpression(fe)
        case (_, _, _) => qs.withMaxResultSize(maxResultSize.getOrElse(20))
      }
    }
    val maxSize = (rangeKeyCondition, filterValueMap, filterExpression) match {
      case (None, None, None) => Some(maxResultSize.getOrElse(20))
      case (_, _, _) => None
    }
    for {
      eventTable <- eventTable(None)
      events <- queryTable(eventTable, querySpecs, { item: Item => item.getLong("timestamp") }.some, maxSize)
    } yield events
  }

  def getServiceNames: Task[List[String]] = {
    for {
      table <- configTable(None)
      service <- Task(table.scan(new ScanSpec().withAttributesToGet("name")).asScala.toSeq.map(_.getString("name")).distinct)(awsExecutorService)
    } yield service.toList
  }

}
