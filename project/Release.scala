import java.io.{ByteArrayInputStream, ByteArrayOutputStream, File}
import java.nio.file.{Files, Paths}

import com.amazonaws.services.s3.model._
import com.amazonaws.services.s3.transfer.Transfer.TransferState
import com.amazonaws.services.s3.transfer.TransferManager
import com.amazonaws.util.IOUtils
import sbt._

import scalaz.concurrent.Task

object Release {

  lazy val releaseCli = taskKey[Unit]("Releases ION-Roller CLI")

  def release(ver: String, zip: File, install: File) = {
    val files = Seq(
      (install.getName, replaceVersionAndReadBytes(ver, install), "text/plain"),
      (zip.getName, readBytes(zip), "application/zip"))
    val tx = new TransferManager
    val tasks = for {
      f <- files
    } yield uploadFile(tx, f._1, f._2, f._3)
    val t = for {
      results <- Task.gatherUnordered(tasks)
      finalResult = if (results.forall(_ == TransferState.Completed)) TransferState.Completed else TransferState.Failed
      printTask <- Task.delay(println(finalResult))
    } yield printTask
    t.run
  }

  def uploadFile(tx: TransferManager, name: String, getBytes: Task[Array[Byte]], contentType: String): Task[TransferState] = {
    for {
      bytes <- getBytes
      meta <- metadata(bytes, contentType)
      transferState <- upload(tx, bytes, name, meta)
    } yield transferState
  }

  def metadata(bytes: Array[Byte], contentType: String): Task[ObjectMetadata] = {
    Task.delay({
      val out = new ByteArrayOutputStream
      out.write(bytes)
      val metadata = new ObjectMetadata
      metadata.setContentType(contentType)
      val contentBytes = IOUtils.toByteArray(new ByteArrayInputStream(out.toByteArray)).length.toLong
      // we need to call new ByteArrayInputStream again, as checking the length reads the stream
      metadata.setContentLength(contentBytes)
      metadata
    })
  }

  def upload(tx: TransferManager, in: Array[Byte], name: String, meta: ObjectMetadata): Task[TransferState] = {
    Task.delay({
      println(s"Uploading $name...")
      val upload = tx.upload(
        new PutObjectRequest("ionroller-cli", name, new ByteArrayInputStream(in), meta)
          .withCannedAcl(CannedAccessControlList.PublicRead)
      )
      while (!upload.isDone) {
        Thread.sleep(2000)
        println(upload.getProgress.getPercentTransferred.toInt + "%")
      }
      upload.getState
    })
  }

  def replaceVersionAndReadBytes(ver: String, file: File): Task[Array[Byte]] = {
    Task.delay({
      scala.io.Source.fromFile(file).getLines()
        .map(in => if (in startsWith "VERSION=") s"VERSION=$ver" else in)
        .mkString("\n")
        .getBytes
        .toSeq
        .toArray
    })
  }

  def readBytes(file: File): Task[Array[Byte]] = Task.delay({
    Files.readAllBytes(Paths.get(file.getAbsolutePath))
  })

}
