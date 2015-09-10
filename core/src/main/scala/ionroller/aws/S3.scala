package ionroller.aws

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.s3.AmazonS3Client

import scalaz.Kleisli
import scalaz.concurrent.Task

object S3 {
  val client: Kleisli[Task, AWSCredentialsProvider, AmazonS3Client] = {
    Kleisli { credentialsProvider =>
      Task(new AmazonS3Client(credentialsProvider))(awsExecutorService)
    }
  }
}
