package ionroller.cmd

import com.ning.http.client._
import com.typesafe.scalalogging.StrictLogging
import scodec.bits.ByteVector

import scala.concurrent.duration._
import scalaz._
import scalaz.Scalaz._
import scalaz.concurrent.Task
import scalaz.stream._

sealed trait SSEMessage extends Product with Serializable
case object SSEEmit extends SSEMessage
case object SSEIgnore extends SSEMessage
final case class SSEId(id: String) extends SSEMessage
final case class SSEEventName(name: String) extends SSEMessage
final case class SSERetryTime(reconnectionTimeMs: Long) extends SSEMessage
final case class SSEData(data: String) extends SSEMessage

final case class SSEState(id: Option[String], eventName: Option[String], data: String)
final case class SSEEvent(eventName: Option[String], data: String)

object ServerSentEvents extends StrictLogging {

  implicit val scheduler = scalaz.concurrent.Strategy.DefaultTimeoutScheduler

  // Process that takes an AsyncHttpClient and parameters, and emits a
  // stream of ByteVectors as chunks of data are received. Used as a
  // source of data for SSE messages, but is more generalized to be
  // an incremental emitter of byes.
  def incrementalHttpRequestProcess(
    client: AsyncHttpClient,
    url: String,
    queryParams: Seq[(String, String)],
    lastEventId: Option[String]
  ): Process[Task, ByteVector] = {
    val req = client.prepareGet(url)

    queryParams foreach { case (name, value) => req.addQueryParam(name, value) }
    lastEventId foreach { req.addHeader("Last-Event-ID", _) }
    req.addHeader("Cache-Control", "no-cache")

    logger.debug(s"Preparing GET request to URL=${url} queryParameters=${queryParams.mkString(",")}")

    val contentQueue = async.boundedQueue[ByteVector](500)

    req.execute(new AsyncCompletionHandler[Unit] {

      override def onStatusReceived(status: HttpResponseStatus) = {
        if (status.getStatusCode > 200 && status.getStatusCode < 300) {
          contentQueue.close.run
          logger.debug(s"Status code ${status.getStatusCode} received, stopping requests")
          AsyncHandler.STATE.ABORT
        } else {
          logger.debug(s"Status code ${status.getStatusCode} received")
          super.onStatusReceived(status)
        }
      }

      override def onCompleted(r: Response): Unit = {
        logger.debug("Request completed")
        contentQueue.close.run
      }

      override def onThrowable(t: Throwable): Unit = {
        logger.debug("Request failed with error", t)
        contentQueue.fail(t).run
      }

      override def onBodyPartReceived(content: HttpResponseBodyPart) = {
        logger.debug(s"Received body part: ${new String(content.getBodyPartBytes, "UTF-8")}")
        contentQueue.enqueueOne(ByteVector(content.getBodyByteBuffer)) attemptRun match {
          case \/-(s) =>
          case -\/(f) =>
            logger.debug("Received body part after closing queue")
        }
        super.onBodyPartReceived(content)
      }
    })

    contentQueue.dequeue
  }

  private[cmd] def splitSingleLine(linesToEmit: List[ByteVector], buffer: ByteVector): (List[ByteVector], ByteVector) = {
    val idx = (0 until buffer.size).find(i => buffer.get(i) == '\n')

    idx match {
      case None =>
        (linesToEmit.reverse, buffer)
      case Some(i) =>
        val str = buffer.consume(i + 1)(d => Right(d))
        str.fold(err => (linesToEmit.reverse, buffer), { case (newBuf, extracted) => splitSingleLine(extracted :: linesToEmit, newBuf) })
    }
  }

  def appendAndEmitByLines(s: (List[ByteVector], ByteVector), b: ByteVector): (List[ByteVector], ByteVector) = {
    splitSingleLine(Nil, s._2 ++ b)
  }

  // Collect ByteVector chunks, look for line endings, and split by those
  // line endings. Emits all complete lines found so far.
  val emitLines: Process1[ByteVector, ByteVector] = {
    process1.scan((List.empty[ByteVector], ByteVector.empty))(appendAndEmitByLines)
      .flatMap(lines => Process.emitAll(lines._1))
  }

  private[cmd] def convertToMessage(s: String): Option[SSEMessage] = {
    val s1 = s.replaceFirst("\r", "").replaceFirst("\n", "")

    val s2 = if (s1.endsWith("\n"))
      s1.dropRight(1)
    else
      s1

    val s3 = if (s2.endsWith("\r"))
      s2.dropRight(1)
    else
      s2

    if (s3.isEmpty) {
      SSEEmit.some
    } else if (s3 == ":") {
      none
    } else if (s3.startsWith("data:")) {
      SSEData(s3.drop(6)).some
    } else if (s3.startsWith("id:")) {
      SSEId(s3.drop(4)).some
    } else if (s3.startsWith("event:")) {
      SSEEventName(s3.drop(7)).some
    } else
      none
  }

  // Convert each line ByteVector to UTF-8, convert into an SSEMessage
  // and emit valid messages.
  val emitMessages: Process1[ByteVector, SSEMessage] = {
    text.utf8Decode
      .map(convertToMessage)
      .pipe(process1.stripNone)
  }

  def setSSEFields(scanState: (SSEState, Option[SSEEvent]), msg: SSEMessage): (SSEState, Option[SSEEvent]) = {
    val oldState = scanState._1

    msg match {
      case SSEEmit => (oldState.copy(data = ""), SSEEvent(oldState.eventName, oldState.data).some)
      case SSEIgnore => (oldState, none)
      case SSEData(d) => (oldState.copy(data = oldState.data + "\n" + d), none)
      case SSEId(id) => (oldState.copy(id = id.some), none)
      case SSEEventName(eventName) => (oldState.copy(eventName = eventName.some), none)
      case SSERetryTime(retryTime) => (oldState, none) // Ignore, already dealt with
    }
  }

  // Collect SSEMessage messages, until an SSEEmit is found, then emit the
  // collected information in an SSEEvent.
  val emitEvents: Process1[SSEMessage, SSEEvent] = {
    process1.scan((SSEState(none, none, ""), none[SSEEvent]))(setSSEFields)
      .collect { case (state, Some(event)) => event }
  }

  def source(client: AsyncHttpClient, url: String, queryParams: Seq[(String, String)]): Process[Task, SSEEvent] = {
    // Stores delay before retrying connection, after failure
    val defaultRetryTime = async.signalOf(3000.millis)

    // Stores last seen ID in SSE stream
    val lastSeenId = async.signalOf(none[String])

    val setDefaultRetryTime: Sink[Task, SSEMessage] = {
      Process.constant {
        case SSERetryTime(retryTime) =>
          defaultRetryTime.set(Duration(retryTime, MILLISECONDS))
        case SSEId(id) =>
          lastSeenId.set(id.some)
        case _ =>
          Task.now(())
      }
    }

    // Wait for the specified retry delay, then make another request with the correct
    // last-seen ID.
    lazy val retryRequest: Process[Task, SSEEvent] = {
      Process.eval(Nondeterminism[Task].both(defaultRetryTime.get, lastSeenId.get)) flatMap {
        case (retryTime, lastId) =>
          time.sleep(retryTime) ++ singleRequest(lastId)
      }
    }

    // Makes a single request to the HTTP server.
    // May retry after a delay if the connection is dropped in certain
    // cases.
    def singleRequest(lastEventId: Option[String]): Process[Task, SSEEvent] = {
      incrementalHttpRequestProcess(client, url, queryParams, lastEventId)
        .pipe(emitLines)
        .pipe(emitMessages)
        .observe(setDefaultRetryTime)
        .pipe(emitEvents)
        .partialAttempt {
          case e: java.net.ConnectException => retryRequest
          case e: java.util.concurrent.TimeoutException => retryRequest
        }
        .map(_.merge)
    }

    singleRequest(none)
  }
}
