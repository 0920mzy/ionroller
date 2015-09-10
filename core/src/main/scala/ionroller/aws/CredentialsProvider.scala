package ionroller.aws

import com.amazonaws.auth.{AWSCredentialsProvider, STSAssumeRoleSessionCredentialsProvider}

import scalaz.concurrent.Task

object CredentialsProvider {
  def apply(role: String): Task[AWSCredentialsProvider] = {
    Task(new STSAssumeRoleSessionCredentialsProvider(role, "ionroller"))(awsExecutorService)
  }
}