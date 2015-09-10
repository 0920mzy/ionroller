package ionroller

import com.amazonaws.services.elasticbeanstalk.model.ConfigurationOptionSetting
import org.joda.time.DateTime
import org.scalacheck.{Arbitrary, Gen}
import play.api.libs.json.JsObject

import scala.concurrent.duration._

object Generators {

  // Scalacheck seems to enjoy making empty strings
  val genNonEmptyString: Gen[String] = for {
    s <- Gen.alphaStr
    c <- Gen.alphaChar
  } yield s :+ c

  val releaseVersionGen: Gen[ReleaseVersion] = for {
    version <- genNonEmptyString
  } yield ReleaseVersion(version)

  val dockerImageGen: Gen[DockerImage] = for {
    server <- Gen.option(genNonEmptyString)
    account <- genNonEmptyString
    image <- genNonEmptyString
    version <- releaseVersionGen
  } yield {
    DockerImage(DockerRepository(s"${server.fold("")(_ + "/")}$account/$image"), version)
  }

  val portMappingGen: Gen[PortMapping] = for {
    containerPort <- Gen.choose(1, 65535)
    hostPort <- Gen.choose(1, 65535)
  } yield PortMapping(containerPort, hostPort)

  val volumeMappingGen: Gen[VolumeMapping] = for {
    containerMapping <- Gen.alphaStr
    hostMapping <- Gen.alphaStr
  } yield VolumeMapping(containerMapping, hostMapping)

  val configurationOptionSettingGen: Gen[ConfigurationOptionSetting] = for {
    namespace <- Gen.alphaStr
    optionName <- Gen.alphaStr
    value <- Gen.alphaStr
  } yield new ConfigurationOptionSetting().withNamespace(namespace).withOptionName(optionName).withValue(value)

  val ebConfigurationGen: Gen[EBSConfiguration] = for {
    deploymentBucket <- Gen.alphaStr
    solutionStack <- Gen.alphaStr
    envOptionSettings <- Gen.listOf(configurationOptionSettingGen)
    jsObject <- Gen.option(Gen.const(JsObject(Seq.empty)))
  } yield EBSConfiguration(deploymentBucket, solutionStack, envOptionSettings, jsObject, jsObject, jsObject, jsObject, jsObject, jsObject, jsObject, jsObject, jsObject)

  def timelineConfigurationGen(awsAccountIds: Seq[String]): Gen[TimelineConfiguration] = for {
    url <- Gen.alphaStr
    hostedZoneId <- Gen.alphaStr
    dockerImage <- dockerImageGen.map(_.repository)
    awsAccountId <- Gen.oneOf(awsAccountIds)
    serviceRole <- Gen.alphaStr
    portMappings <- Gen.nonEmptyListOf(portMappingGen)
    runArgs <- Gen.listOf(Gen.alphaStr)
    volumeMappings <- Gen.listOf(volumeMappingGen)
    removeUnusedAfter <- Gen.option(Gen.posNum[Int].map(_.minutes))
    ebsConfig <- ebConfigurationGen
  } yield TimelineConfiguration(url, hostedZoneId, None, dockerImage, awsAccountId, serviceRole, portMappings, volumeMappings, runArgs, removeUnusedAfter, ebsConfig, new DateTime)

  val systemConfigurationWithEnabledTimelineGen: Gen[SystemConfiguration] = for {
    awsAccountIds <- Gen.listOf(Gen.numStr)
    tlConfig <- timelineConfigurationGen(awsAccountIds)
  } yield SystemConfiguration(Map(ConfigurationManager.modifyEnvironmentsWhitelist.head -> tlConfig))

  val systemConfigurationGen: Gen[SystemConfiguration] = for {
    awsAccountIds <- Gen.listOf(Gen.numStr)
    timelines <- Gen.mapOf(Gen.zip(Gen.alphaStr.map(TimelineName.apply), timelineConfigurationGen(awsAccountIds)))
  } yield SystemConfiguration(timelines)

  implicit val releaseVersionArb: Arbitrary[ReleaseVersion] = Arbitrary(releaseVersionGen)
  implicit val dockerImageArb: Arbitrary[DockerImage] = Arbitrary(dockerImageGen)
  implicit val ebConfigArb: Arbitrary[EBSConfiguration] = Arbitrary(ebConfigurationGen)
  implicit val systemConfigurationArb: Arbitrary[SystemConfiguration] = Arbitrary(systemConfigurationGen)
}