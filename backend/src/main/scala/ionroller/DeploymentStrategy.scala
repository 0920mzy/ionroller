package ionroller

import scalaz.concurrent.Task
import scalaz.stream.Sink

trait DeploymentStrategy {
  def deploymentCalls(desiredStateSink: Sink[Task, (TimelineName, DesiredTimelineState)]): Seq[TimelineData => ActionResultOrNext]
}

