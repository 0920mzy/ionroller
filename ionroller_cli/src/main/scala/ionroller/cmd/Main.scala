package ionroller.cmd

import java.io.{FileWriter, File}
import java.net.{MalformedURLException, URL}

import com.gilt.ionroller.api.v0.Client
import com.gilt.ionroller.api.v0.errors.FailedRequest
import com.gilt.ionroller.api.v0.models.{Release, Service, ServiceConfig}
import com.ning.http.client.{AsyncCompletionHandler, AsyncHttpClient, AsyncHttpClientConfig, Response}
import com.typesafe.scalalogging.StrictLogging
import ionroller._
import ionroller.aws.AWSClientCache
import ionroller.deployment.eb.EBSDeployer
import org.fusesource.jansi._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.io.StdIn
import scala.concurrent.duration._
import scalaz.concurrent.{Task, TaskApp}
import scalaz.std.option._
import scalaz.stream._
import scalaz.syntax.either._
import scalaz.syntax.std.option._
import scalaz.{OptionT, -\/}

final case class CmdAndParams(cmd: Command, baseUrl: Option[String])

object Main extends TaskApp with StrictLogging {

  val configFilePath = s"${System.getProperty("user.home")}/.config/ionroller/config.ionroller"
  val baseUrlKey = "baseUrl"
  val cliUpdateUrlKey = "cliUpdateUrl"

  val configFilePattern = (key: String) => s"^$key=(\\S+)".r

  val configEntry = (key: String, value: String) => s"$key=$value"

  implicit def scalaFutToTask[A](f: Future[A]): Task[A] = {
    Task.async {
      cb =>
        f.onComplete {
          case scala.util.Success(s) => cb(s.right)
          case scala.util.Failure(f) => cb(f.left)
        }
    }
  }

  def release(client: Client, asyncClient: AsyncHttpClient, baseUrl: String, service: String, version: String, configTimestamp: Option[DateTime]): Task[Unit] = {
    scalaFutToTask(client.Services.postReleaseByServiceName(service, version, configTimestamp)) flatMap { from =>
      val params: Seq[(String, String)] = Seq(
        "service" -> service,
        "version" -> version,
        "from" -> from.toString
      )
      statusUpdates(asyncClient, baseUrl, params, { j =>
        val str = (j \ "type").asOpt[String]
        str == "TrafficMoved".some || str == "CommandIgnoredEvent".some
      })
    }
  }

  def drop(client: Client, asyncClient: AsyncHttpClient, baseUrl: String, service: String, version: String, configTimestamp: Option[DateTime], force: Boolean): Task[Unit] = {
    scalaFutToTask(client.Services.postDropByServiceName(service, version, configTimestamp, force)) flatMap { from =>
      val params: Seq[(String, String)] = Seq(
        "service" -> service,
        "version" -> version,
        "from" -> from.toString
      )
      statusUpdates(asyncClient, baseUrl, params, { j =>
        {
          val str = (j \ "type").asOpt[String]
          str == "TrafficMoved".some || str == "CommandIgnoredEvent".some
        }
      })
    }
  }

  def getConfig(client: Client, service: String, timestamp: Option[DateTime]): Task[Option[ServiceConfig]] = {
    timestamp.fold(client.Services.getConfigByServiceName(service).map(_.some).recover {
      case FailedRequest(404, _, _) => None
    })(t => client.Services.getConfigsByServiceNameAndTimestamp(service, t).map(_.some).recover {
      case FailedRequest(404, _, _) => None
    })
  }

  def getConfigs(client: Client, service: String, from: Option[DateTime], to: Option[DateTime]): Task[Seq[Option[DateTime]]] = {
    client.Services.getConfigsByServiceName(service, from, to).map({ sc => sc.map(_.timestamp) })
  }

  def readStringFromFile(file: String): Task[String] = {
    Task.delay {
      val fileSource = scala.io.Source.fromFile(file)
      val str = fileSource.mkString
      fileSource.close()
      str
    }
  }

  val readStringFromStdin: Task[String] = {
    Task.delay {
      val inBuffer = new StringBuilder
      Iterator.continually(Option(scala.io.StdIn.readLine())).takeWhile(in => in.isDefined).foreach(in => inBuffer.append(in.get))
      inBuffer.mkString
    }
  }

  def readStringFromFileOrStdin(file: Option[String]): Task[String] = {
    file.fold(readStringFromStdin)(readStringFromFile)
  }

  def setConfig(client: Client, service: String, file: Option[String]): Task[String] = {
    val serviceConfigReads = com.gilt.ionroller.api.v0.models.json.jsonReadsIonrollerAPIServiceConfig
    (for {
      json <- readStringFromFileOrStdin(file)
      serviceConfig <- Task(Json.parse(json).as[ServiceConfig](serviceConfigReads))
      putResult <- client.Services.putConfigByServiceName(service, serviceConfig).map(_ => "Configuration updated.")
    } yield putResult).handleWith({
      case fr @ FailedRequest(404, message, _) => Task(s"$message ${fr.message}")
      case t => Task(s"Invalid JSON: ${t.getMessage}}")
    })
  }

  def deleteConfig(client: Client, service: String): Task[String] = {
    (for {
      deleteResult <- client.Services.deleteConfigByServiceName(service).map(_ => "Configuration removed.")
    } yield deleteResult).handleWith({
      case fr @ FailedRequest(412, message, _) => Task("Service must have no running instances. Drop current version first.")
      case fr @ FailedRequest(404, message, _) => Task("Service configuration not found.")
      case t => Task(s"Error: ${t.getMessage}")
    })
  }

  def update(client: AsyncHttpClient, cliUpdateUrl: String): Task[Int] = {
    import sys.process._
    Task.async[Int](
      k => {
        val req = client.prepareGet(cliUpdateUrl)
        req.execute[Unit](new AsyncCompletionHandler[Unit] {
          override def onCompleted(r: Response): Unit = {
            val script: Task[File] = Task.delay {
              val s = File.createTempFile("ionroller", "install")
              s.deleteOnExit()
              s
            }

            def writeFile(out: java.io.PrintWriter) = Task.delay({
              Iterator
                .continually(r.getResponseBodyAsStream.read)
                .takeWhile(_ != -1)
                .foreach(out.write)
            })

            def runScript(f: File): Task[Int] = {
              Task.delay(Process("sh", Seq(f.getAbsolutePath)).!)
            }

            val results = for {
              s <- script
              writer = new java.io.PrintWriter(s)
              _ <- writeFile(writer).onFinish(_ => Task.delay(writer.close()))
              results <- runScript(s)
            } yield results

            k(results.attemptRun)
          }

          override def onThrowable(t: Throwable): Unit = k(-\/(t))
        })
        ()
      }
    )
  }

  val parser = DateTimeFormat.forPattern("YY-MM-dd HH:mm:ss") //ISODateTimeFormat.dateTimeNoMillis()

  def convertJson(e: SSEEvent): Task[Option[JsValue]] = {
    Task.delay(Json.parse(e.data).some).handleWith {
      case e: Exception =>
        Task.delay {
          logger.error(e.getMessage)
          none
        }
    }
  }

  def convertToString(j: JsValue): Task[Option[String]] = {
    Task.delay {
      val time = new DateTime((j \ "timestamp").as[Long] / 1000)
      val t = (j \ "type").as[String]
      t match {
        case "await" => none
        case _ =>
          val service = (j \ "service").asOpt[String].getOrElse("")
          val version = (j \ "version").asOpt[String].getOrElse("")
          val message = (j \ "message").as[String]
          val user = (j \ "user").asOpt[String].getOrElse("")
          val msg = Seq(service, version, message, user).filterNot(_.isEmpty).mkString(" ")
          s"[${time.toString(parser)}] $msg".some
      }
    } handleWith {
      case e: Exception =>
        Task.delay {
          logger.error(e.getMessage, e)
          none
        }
    }
  }

  def statusUpdates(client: AsyncHttpClient, baseUrl: String, params: Seq[(String, String)], dropAfter: JsValue => Boolean): Task[Unit] = {
    AnsiConsole.systemInstall()

    val url = s"$baseUrl/feed"

    val jsonSource =
      ServerSentEvents.source(client, url, params)
        .through(channel.lift(convertJson))
        .pipe(process1.stripNone)
        .takeThrough(i => !dropAfter(i))
        .through(channel.lift(convertToString))
        .pipe(process1.stripNone)

    ProgressMessage.progressMessage(jsonSource, 100.millis)
      .to(io.stdOutLines)
      .run
  }

  def emergencyRelease(service: String, version: String, configTimestamp: Option[DateTime]): Task[Unit] = {
    val timelineName = TimelineName(service)

    val configTask: OptionT[Task, TimelineConfiguration] = {
      OptionT {
        ConfigurationManager.getSavedConfiguration.map(_.timelines.get(timelineName))
      }
    }

    val deploymentTask = for {
      config <- configTask
      awsClientCache <- OptionT(AWSClientCache.getCache(config.ionrollerRoleArn).map(_.some))
      runtimeConfig = RuntimeConfiguration(config.ionrollerRoleArn, config.portMappings, config.runArgs, config.volumeMappings, config.timestamp)
      envDetails = EBSSetup(DockerImage(config.dockerImage, ReleaseVersion(version)), runtimeConfig, config.ebsConfig)
      deployer = EBSDeployer(TimelineName(service), config.url, config.externalElb, envDetails)
      deployer <- OptionT(deployer.deploy(awsClientCache).map(_.some))
    } yield deployer

    deploymentTask.run flatMap {
      case Some(deploymentResult) => Task.delay(println(deploymentResult))
      case None => Task.delay(println("Configuration not found"))
    }
  }

  def run(client: Client, asyncClient: AsyncHttpClient, baseUrl: String): Command => Task[Unit] = {

    case CmdRelease(service, version, configTimestamp, emergency) =>
      if (!emergency) {
        serviceState(client, service) flatMap {
          serviceState: ServiceState =>
            serviceState.currentState foreach println
            serviceState.service match {
              case None =>
                println(s"No such service. (Is $service configured?/Is the name correct?)")
                Task.now(())
              case Some(srv) =>
                val curRelease = for {
                  desired <- srv.state.desired
                  curEnv <- desired.current
                } yield curEnv
                val curVersionString = curRelease.fold(" (no version currently deployed).")(s => s" (this will replace current version ${s.tag}).")
                println(s"Releasing $service version $version$curVersionString")
                release(client, asyncClient, baseUrl, service, version, configTimestamp)
            }
        }
      } else emergencyRelease(service, version, configTimestamp)

    case CmdCurrent(service) =>
      serviceState(client, service) flatMap {
        serviceState: ServiceState =>
          serviceState.currentState foreach println
          Task.now(())
      }

    case CmdDrop(service, version, configTimestamp, force) =>
      serviceState(client, service) flatMap {
        serviceState: ServiceState =>
          serviceState.service match {
            case None =>
              println(s"No such service. (Is $service configured?/Is the name correct?)")
              Task.now(())
            case Some(srv) => {
              serviceState.currentState foreach println
              println(s"Dropping $service version $version (force=$force)")
              drop(client, asyncClient, baseUrl, service, version, configTimestamp, force)
            }
          }
      }

    case CmdEvents(service, version, from, to) =>

      val params: Seq[(String, String)] = Seq(
        "service" -> service
      ) ++
        version.map(v => "version" -> v) ++
        from.map(f => "from" -> (f.getMillis * 1000).toString) ++
        to.map(t => "to" -> (t.getMillis * 1000).toString)

      statusUpdates(asyncClient, baseUrl, params, _ => false)

    case CmdVersion =>
      print(s"${ionroller.BuildInfo.version}")
      Task(())

    case CmdConfig(service, timestamp) =>
      getConfig(client, service, timestamp) map {
        case None => println("No such service.")
        case Some(c) => println(Json.prettyPrint(Json.toJson(c)(com.gilt.ionroller.api.v0.models.json.jsonWritesIonrollerAPIServiceConfig)))
      }

    case CmdConfigs(service, from, to) =>
      getConfigs(client, service, from, to) map { seq => println(seq.collect({ case Some(c) => c }).mkString("\n")) }

    case CmdSetConfig(service, file) =>
      setConfig(client, service, file) map println

    case CmdDeleteConfig(service) =>
      deleteConfig(client, service) map println

    case CmdUpdate =>
      println("Updating ION-Roller...")
      for {
        cliUpdateUrl <- getFromConfig(cliUpdateUrlKey, "Enter ION-Roller CLI update URL:")
        _ <- update(asyncClient, cliUpdateUrl)
      } yield Task(())

    case CmdSetup =>
      println("Setting up ION-Roller...")

      Setup.setup.onFinish {
        // TODO: Issues quitting client here, nuking from orbit
        case _ => Task.delay {
          Thread.sleep(1000); sys.exit(0)
        }
      }

    case CmdSetBaseUrl(baseUrl) =>
      writeToConfigFile(baseUrlKey, baseUrl).flatMap(_ => Task.delay(println(s"Base url set to $baseUrl")))

    case CmdSetCliUpdateUrl(updateUrl) =>
      writeToConfigFile(cliUpdateUrlKey, updateUrl).flatMap(_ => Task.delay(println(s"CLI update url set to $updateUrl")))
  }

  def serviceState(client: Client, service: String): Task[ServiceState] = {
    val serviceTask: Task[Option[Service]] = client.Services.getByServiceName(service).map(_.some).recover {
      case FailedRequest(404, _, _) => None
    }
    serviceTask flatMap { serviceInfo: Option[Service] =>
      serviceInfo match {
        case None => Task(ServiceState(None, s"No such service.".some))
        case Some(srv) => {
          val serviceState = srv.state.desired map { desired =>
            val (currentRelease: Option[Release], nextRelease: Option[Release], futureRelease: Option[Release]) = (desired.current, desired.next, desired.future)
            s"current: ${currentRelease.fold("No version deployed")(c => c.tag)}" +
              nextRelease.fold("")(n => s"\n   next: ${n.tag}") +
              futureRelease.fold("")(f => s"\n future: ${f.tag}")
          }
          Task(ServiceState(srv.some, serviceState))
        }
      }
    }
  }

  val getIdentity: Task[Option[String]] = {
    import sys.process._

    Task.delay(Process("git config user.email").lineStream.headOption).handleWith {
      case e: Exception =>
        Task.delay {
          println("[WARN]: User identity not known.")
          logger.error(e.getMessage, e)
          None
        }
    }
  }

  val getHeaders: Task[Seq[(String, String)]] = {
    for {
      identity <- getIdentity
    } yield identity.toSeq.map(u => "User" -> u)
  }

  def appendToConfigFile(key: String, url: String) = {
    val file = new File(configFilePath)
    val parentDir = file.getParentFile
    if (null != parentDir) parentDir.mkdirs
    val fw = new FileWriter(configFilePath, true)
    try fw.append(s"${configEntry(key, url)}\n")
    finally fw.close()
  }

  def updateConfigFile(key: String, url: String) = {
    {
      for {
        line <- io.linesR(configFilePath)
      } yield line.replaceFirst(configFilePattern(key).toString(), configEntry(key, url))
    }.runLog.map {
      in =>
        val fw = new FileWriter(configFilePath, false)
        try in.foreach(line => fw.append(s"$line\n"))
        finally fw.close()
    }.run
  }

  def writeToConfigFile(key: String, url: String): Task[Unit] = {
    new File(configFilePath) match {
      case f if f.exists() =>
        val pattern = configFilePattern(key)
        io.linesR(configFilePath).collectFirst({
          case pattern(u) => u
        }).runLast.map {
          case Some(entry) => updateConfigFile(key, url)
          case None => appendToConfigFile(key, url)
        }
      case _ => Task.delay(appendToConfigFile(key, url))
    }
  }

  def readUrlFromStandardInput(message: String): Task[String] =
    Task.delay {
      println(message)
      new URL(StdIn.readLine()).toString
    }.handleWith {
      case ex: MalformedURLException =>
        println(s"Invalid URL")
        readUrlFromStandardInput(message)
    }

  def getFromConfig(key: String, message: String): Task[String] =
    (new File(configFilePath) match {
      case f if f.exists() =>
        val pattern = configFilePattern(key)
        io.linesR(configFilePath).collectFirst({
          case pattern(url) => url
        }).runLast
      case _ => Task.delay(None)
    }).flatMap {
      case Some(u) => Task.delay(u)
      case None => for {
        url <- readUrlFromStandardInput(message)
        _ <- writeToConfigFile(key, url)
      } yield url
    }

  val getAsyncHttpClient: Task[AsyncHttpClient] =
    Task.delay {
      val clientConfig = new AsyncHttpClientConfig.Builder()
        .setAcceptAnyCertificate(true)
        .build()
      new AsyncHttpClient(clientConfig)
    }

  def getApidocClient(params: CmdAndParams, headers: Seq[(String, String)], asyncHttpClient: AsyncHttpClient, baseUrl: String): Task[Client] =
    Task.delay {
      new Client(apiUrl = baseUrl, defaultHeaders = headers, asyncHttpClient = asyncHttpClient)
    }

  override def runl(args: List[String]): Task[Unit] = {
    for {
      params <- IonrollerParser.cmdAndParams(args)
      baseUrl <- (params.baseUrl, params.cmd) match {
        case (Some(url), _) => Task.delay(url)
        case (None, CmdSetBaseUrl(url)) => Task.delay(url)
        case (None, _) => getFromConfig(baseUrlKey, "Enter ION-Roller service URL:")
      }
      headers <- getHeaders
      asyncHttpClient <- getAsyncHttpClient
      client <- getApidocClient(params, headers, asyncHttpClient, baseUrl)
      runCommand <- run(client, asyncHttpClient, baseUrl)(params.cmd).onFinish(_ => Task.delay(asyncHttpClient.close()))
    } yield runCommand
  }
}
