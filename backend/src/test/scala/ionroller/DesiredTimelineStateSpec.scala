package ionroller

import org.joda.time.DateTime
import org.scalatest._
import play.api.libs.json.JsObject

class DesiredTimelineStateSpec extends FlatSpec with Matchers {

  val dockerRepository = DockerRepository("account", "image")
  val ebConfiguration = EBSConfiguration("deploymentBucket", "solutionStack", Seq.empty, Some(JsObject(Seq.empty)), Some(JsObject(Seq.empty)), Some(JsObject(Seq.empty)), Some(JsObject(Seq.empty)), Some(JsObject(Seq.empty)), Some(JsObject(Seq.empty)), Some(JsObject(Seq.empty)), Some(JsObject(Seq.empty)), Some(JsObject(Seq.empty)))
  val setupArn = "arn:aws:iam::830967614603:role/ionroller"
  val mappings = Seq(PortMapping(9000, 9000))
  val date = new DateTime
  val runtimeConfiguration = RuntimeConfiguration(setupArn, mappings, List(), List(), date)

  "A desired timeline state" should "start next deployment correctly" in {

    val cur = EBSSetup(DockerImage(dockerRepository, ReleaseVersion("0.0.1")), runtimeConfiguration, ebConfiguration)
    val next = EBSSetup(DockerImage(dockerRepository, ReleaseVersion("0.0.2")), runtimeConfiguration, ebConfiguration)
    def fut = EBSSetup(DockerImage(dockerRepository, ReleaseVersion("0.0.3")), runtimeConfiguration, ebConfiguration)

    val desiredState = DesiredTimelineState(Seq.empty, Some((cur, Some((next, Some(fut))))))

    val newDesired = desiredState.startNextDeployment

    newDesired should be(DesiredTimelineState(Seq.empty, Some((next, Some((fut, None))))))

    val nextNewDesired = newDesired.startNextDeployment

  }

  it should "add a new version correctly" in {
    val cur = EBSSetup(DockerImage(dockerRepository, ReleaseVersion("0.0.1")), runtimeConfiguration, ebConfiguration)
    val next = EBSSetup(DockerImage(dockerRepository, ReleaseVersion("0.0.2")), runtimeConfiguration, ebConfiguration)
    def fut = EBSSetup(DockerImage(dockerRepository, ReleaseVersion("0.0.3")), runtimeConfiguration, ebConfiguration)
    def fut2 = EBSSetup(DockerImage(dockerRepository, ReleaseVersion("0.0.4")), runtimeConfiguration, ebConfiguration)

    val desiredState = DesiredTimelineState(Seq.empty, None)

    val newDesired = desiredState.addNewVersion(cur)

    newDesired should be(DesiredTimelineState(Seq.empty, Some((cur, None))))

    val nextNewDesired = newDesired.addNewVersion(next)

    nextNewDesired should be(DesiredTimelineState(Seq.empty, Some((cur, Some((next, None))))))

    val nextNextNewDesired = nextNewDesired.addNewVersion(fut)

    nextNextNewDesired should be(DesiredTimelineState(Seq.empty, Some((cur, Some((next, Some(fut)))))))

    val lastNewDesired = nextNextNewDesired.addNewVersion(fut2)

    lastNewDesired should be(DesiredTimelineState(Seq.empty, Some((cur, Some((next, Some(fut2)))))))
  }

  it should "drop versions without timestamps correctly" in {
    val cur = EBSSetup(DockerImage(dockerRepository, ReleaseVersion("0.0.1")), runtimeConfiguration, ebConfiguration)
    val next = EBSSetup(DockerImage(dockerRepository, ReleaseVersion("0.0.2")), runtimeConfiguration, ebConfiguration)
    def fut = EBSSetup(DockerImage(dockerRepository, ReleaseVersion("0.0.3")), runtimeConfiguration, ebConfiguration)

    val desiredState = DesiredTimelineState(Seq.empty, Some((cur, Some((next, Some(fut))))))

    val droppedFut = desiredState.dropVersion(ReleaseVersion("0.0.3"), None, false)
    droppedFut should be(DesiredTimelineState(Seq(EnvironmentToRemove("0.0.3", None, false)), Some((cur, Some((next, None))))))

    val droppedFutThenNext = droppedFut.dropVersion(ReleaseVersion("0.0.2"), None, false)
    droppedFutThenNext should be(DesiredTimelineState(Seq(EnvironmentToRemove("0.0.3", None, false), EnvironmentToRemove("0.0.2", None, false)), Some((cur, None))))

    val droppedNext = desiredState.dropVersion(ReleaseVersion("0.0.2"), None, false)
    droppedNext should be(DesiredTimelineState(Seq(EnvironmentToRemove("0.0.2", None, false)), Some((cur, Some((fut, None))))))
  }

  it should "drop versions with timestamps correctly" in {
    val cur = EBSSetup(DockerImage(dockerRepository, ReleaseVersion("0.0.1")), runtimeConfiguration, ebConfiguration)
    val next = EBSSetup(DockerImage(dockerRepository, ReleaseVersion("0.0.2")), runtimeConfiguration, ebConfiguration)
    def fut = EBSSetup(DockerImage(dockerRepository, ReleaseVersion("0.0.3")), runtimeConfiguration, ebConfiguration)

    val desiredState = DesiredTimelineState(Seq.empty, Some((cur, Some((next, Some(fut))))))

    val droppedFut = desiredState.dropVersion(ReleaseVersion("0.0.3"), Some(runtimeConfiguration.timestamp), false)
    droppedFut should be(DesiredTimelineState(Seq(EnvironmentToRemove("0.0.3", Some(runtimeConfiguration.timestamp), false)), Some((cur, Some((next, None))))))

    val droppedFutThenNext = droppedFut.dropVersion(ReleaseVersion("0.0.2"), Some(runtimeConfiguration.timestamp), false)
    droppedFutThenNext should be(DesiredTimelineState(Seq(EnvironmentToRemove("0.0.3", Some(runtimeConfiguration.timestamp), false), EnvironmentToRemove("0.0.2", Some(runtimeConfiguration.timestamp), false)), Some((cur, None))))

    val droppedNext = desiredState.dropVersion(ReleaseVersion("0.0.2"), Some(runtimeConfiguration.timestamp), false)
    droppedNext should be(DesiredTimelineState(Seq(EnvironmentToRemove("0.0.2", Some(runtimeConfiguration.timestamp), false)), Some((cur, Some((fut, None))))))
  }

  it should "not include version to drop in current environments" in {
    val cur = EBSSetup(DockerImage(dockerRepository, ReleaseVersion("0.0.1")), runtimeConfiguration, ebConfiguration)
    val next = EBSSetup(DockerImage(dockerRepository, ReleaseVersion("0.0.2")), runtimeConfiguration, ebConfiguration)

    // Note: this shouldn't actually be possible from here onwards (tested in another test)
    val desiredState = DesiredTimelineState(Seq.empty, Some((cur, Some((next, Some(next))))))

    val droppedFut = desiredState.dropVersion(ReleaseVersion("0.0.2"), None, false)
    droppedFut should be(DesiredTimelineState(Seq(EnvironmentToRemove("0.0.2", None, false)), Some((cur, None))))

    val droppedFutThenNext = droppedFut.dropVersion(ReleaseVersion("0.0.2"), None, false)
    droppedFutThenNext should be(DesiredTimelineState(Seq(EnvironmentToRemove("0.0.2", None, false)), Some((cur, None))))
  }

  it should "not add same version if already existing in state" in {
    val cur = EBSSetup(DockerImage(dockerRepository, ReleaseVersion("0.0.1")), runtimeConfiguration, ebConfiguration)
    val next = EBSSetup(DockerImage(dockerRepository, ReleaseVersion("0.0.2")), runtimeConfiguration, ebConfiguration)

    val desiredState = DesiredTimelineState(Seq.empty, Some((cur, None)))

    val addedOnce = desiredState.addNewVersion(next)
    addedOnce should be(DesiredTimelineState(Seq.empty, Some((cur, Some((next, None))))))

    val addedTwice = desiredState.addNewVersion(next)
    addedTwice should equal(addedOnce)
  }
}
