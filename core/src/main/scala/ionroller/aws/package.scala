package ionroller

import java.util.concurrent.{ExecutorService, Executors}

package object aws {
  val awsExecutorService: ExecutorService = Executors.newCachedThreadPool()

  implicit val `| Implicit executor service        |`: ExecutorService = awsExecutorService
  implicit val ` | is disabled - define explicitly  |`: ExecutorService = awsExecutorService
}
