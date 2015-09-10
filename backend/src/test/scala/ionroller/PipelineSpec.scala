package ionroller

import ionroller.Generators.releaseVersionArb
import ionroller.aws.AWSClientCache
import org.joda.time.DateTime
import org.scalacheck.Arbitrary
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import scalaz.Kleisli
import scalaz.concurrent.Task
import scalaz.stream._

class PipelineSpec extends FlatSpec with Matchers with GeneratorDrivenPropertyChecks {
  val nullCache: Kleisli[Task, String, AWSClientCache] = {
    Kleisli { role =>
      ???
    }
  }

  val nullSink: Sink[Task, (TimelineName, DesiredTimelineState)] =
    Process.constant { case (s: TimelineName, d: DesiredTimelineState) => Task.now(()) }

  "An empty config" should "do nothing" in {
    val config = SystemConfiguration(Map.empty)
    val desired = DesiredSystemState(Map.empty)
    val liveState = LiveSystemState(Map.empty)

    val configSignal = async.signalOf(config)
    val desiredSignal = async.signalOf(desired)

    val p = Pipeline(nullCache, nullSink, liveState, configSignal, desiredSignal, SystemData(config, desired, liveState))

    val results = p.actionProcess(ElasticBeanstalkStrategy).runLog.run

    results should be(empty)
  }

  "A config with one missing environment" should "create the environment" in {
    import Generators.systemConfigurationArb

    forAll { (sysConfig: SystemConfiguration, version: ReleaseVersion) =>

      val tl = sysConfig.timelines.headOption

      whenever(tl.isDefined) {
        val (tlName, tlConfig) = tl.get
        val dockerImage = DockerImage(tlConfig.dockerImage, version)

        val desired = DesiredSystemState(Map(tlName -> DesiredTimelineState(Seq.empty, Some((EBSSetup(dockerImage, RuntimeConfiguration(tlConfig.ionrollerRoleArn, tlConfig.portMappings, tlConfig.runArgs, tlConfig.volumeMappings, new DateTime), tlConfig.ebsConfig), None)))))
        val live = LiveSystemState(Map.empty)
        val configSignal = async.signalOf(sysConfig)
        val desiredSignal = async.signalOf(desired)
        val p = Pipeline(nullCache, nullSink, live, configSignal, desiredSignal, SystemData(sysConfig, desired, live))

        val results = p.actionProcess(ElasticBeanstalkStrategy).runLog.run

        results should have size 1

        results.head._2 shouldBe a[CreateEnvironment]

        val ce: CreateEnvironment = results(0)._2.asInstanceOf[CreateEnvironment]

        ce.envDetails.dockerImage shouldEqual dockerImage
        ce.envDetails.ebsConfiguration shouldEqual tlConfig.ebsConfig
      }
    }
  }

  "A system with a set whitelist" should "consider that timeline enabled" in {
    implicit val sysConfigArb = Arbitrary(Generators.systemConfigurationWithEnabledTimelineGen)

    forAll { (sysConfig: SystemConfiguration, version: ReleaseVersion) =>

      val tl = sysConfig.timelines.headOption

      whenever(tl.isDefined) {
        val (tlName, tlConfig) = tl.get
        val dockerImage = DockerImage(tlConfig.dockerImage, version)

        val desired = DesiredSystemState(Map(tlName -> DesiredTimelineState(Seq.empty, Some((EBSSetup(dockerImage, RuntimeConfiguration(tlConfig.ionrollerRoleArn, tlConfig.portMappings, tlConfig.runArgs, tlConfig.volumeMappings, new DateTime), tlConfig.ebsConfig), None)))))
        val live = LiveSystemState(Map.empty)
        val configSignal = async.signalOf(sysConfig)
        val desiredSignal = async.signalOf(desired)
        val p = Pipeline(nullCache, nullSink, live, configSignal, desiredSignal, SystemData(sysConfig, desired, live))

        val results = p.actionProcess(ElasticBeanstalkStrategy).runLog.run

        results.head._1 should be('enabled)
      }
    }
  }

  it should "consider other timelines disabled" in {
    import Generators.systemConfigurationArb

    forAll { (sysConfig: SystemConfiguration, version: ReleaseVersion) =>

      val tl = sysConfig.timelines.headOption

      whenever(tl.isDefined) {
        val (tlName, tlConfig) = tl.get
        val dockerImage = DockerImage(tlConfig.dockerImage, version)

        val desired = DesiredSystemState(Map(tlName -> DesiredTimelineState(Seq.empty, Some((EBSSetup(dockerImage, RuntimeConfiguration(tlConfig.ionrollerRoleArn, tlConfig.portMappings, tlConfig.runArgs, tlConfig.volumeMappings, new DateTime), tlConfig.ebsConfig), None)))))
        val live = LiveSystemState(Map.empty)
        val configSignal = async.signalOf(sysConfig)
        val desiredSignal = async.signalOf(desired)
        val p = Pipeline(nullCache, nullSink, live, configSignal, desiredSignal, SystemData(sysConfig, desired, live))

        val results = p.actionProcess(ElasticBeanstalkStrategy).runLog.run

        results(0)._1 shouldNot be('enabled)
      }
    }
  }
}
