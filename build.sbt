import scalariform.formatter.preferences._
import com.typesafe.sbt.packager.docker._

val awsSdkVersion = "1.10.12"
val awsSdkLibraries = Seq(
  "autoscaling",
  "dynamodb",
  "ec2",
  "elasticbeanstalk",
  "elasticloadbalancing",
  "iam",
  "route53",
  "s3",
  "sts"
)

lazy val awsSdkDependencies = {
  libraryDependencies ++= awsSdkLibraries.map(v => "com.amazonaws" % s"aws-java-sdk-$v" % awsSdkVersion)
}

lazy val circleTestReports = {
  scala.util.Try(sys.env("CIRCLE_TEST_REPORTS"))
    .toOption
    .map(d => Tests.Argument(TestFrameworks.ScalaTest, "-u", d))
    .toSeq
}

lazy val ionrollerScalariformSettings =
  scalariformSettings ++ Seq(
    ScalariformKeys.preferences := ScalariformKeys.preferences.value
      .setPreference(DoubleIndentClassDeclaration, true)
      .setPreference(SpacesAroundMultiImports, false),
    excludeFilter in ScalariformKeys.format := "*NingClient.scala"
  )

lazy val goodWarningSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-language:existentials",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-unchecked",
    "-Xfatal-warnings",
    "-Xlint",
    "-Yno-adapted-args",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard",
    "-Xfuture",
    "-Ywarn-unused-import"
  )
)

lazy val commonSettings = Seq(
  scalaVersion := "2.11.7",
  scalacOptions ++= Seq(
    "-encoding", "UTF-8"
  ),
  testOptions in Test ++= circleTestReports,
  version := "git describe --tags --dirty --always".!!.stripPrefix("v").trim,
  resolvers ++= Seq(
    "Typesafe repository" at "https://repo.typesafe.com/typesafe/releases"
  ),
  maintainer in Docker := "Ouroboros <gcoady@gilt.com>",
  dockerBaseImage := "fiadliel/java8-jre:8u60",
  dockerExposedPorts := Seq(9000),
  dockerRepository := Some("giltouroboros")
)

lazy val www = project
  .enablePlugins(PlayScala, NewRelic)
  .settings(commonSettings ++ Revolver.settings ++ ionrollerScalariformSettings)
  .settings(
    name in Docker := "ionroller",
    normalizedName := "ionroller",
    routesImport += "com.gilt.ionroller.api.v0.Bindables._",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "2.2.4" % "test"
    )
  )
  .dependsOn(api, backend)

lazy val ionroller_cli = project
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging)
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "ionroller",
    resolvers += "bmjames Bintray Repo" at "https://dl.bintray.com/bmjames/maven",
    libraryDependencies ++= Seq(
      "org.scalaz.stream" %% "scalaz-stream" % "0.7.3a",
      "org.fusesource.jansi" % "jansi" % "1.11",
      "net.bmjames" %% "scala-optparse-applicative" % "0.2.1",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0",
      "ch.qos.logback" % "logback-classic" % "1.1.3",
      "com.ning" % "async-http-client" % "1.9.30",
      "com.typesafe.play" %% "play-json" % "2.4.2",
      "org.scalaz" %% "scalaz-concurrent" % "7.1.3"
    ),
    Release.releaseCli := Release.release(version.value, (packageBin in Universal).value, file("ionroller_cli/install"))
  )
  .settings(commonSettings ++ ionrollerScalariformSettings)
  .dependsOn(core)

lazy val api = project
  .settings(
    libraryDependencies += ws
  )
  .settings(commonSettings ++ Revolver.settings)


lazy val core = project
  .settings(
    awsSdkDependencies,
    libraryDependencies ++= Seq(
      "org.scalaz.stream" %% "scalaz-stream" % "0.7.3a",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0",
      "ch.qos.logback" % "logback-classic" % "1.1.3",
      "com.typesafe.play" %% "play-json" % "2.4.2",
      "org.scalaz" %% "scalaz-core" % "7.1.3",
      "org.scalaz" %% "scalaz-concurrent" % "7.1.3",
      "com.github.nscala-time" %% "nscala-time" % "2.0.0",
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.6.1",
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.6.1"
    )
  )
  .settings(commonSettings ++ goodWarningSettings ++ ionrollerScalariformSettings)

lazy val backend = project
  .settings(
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "2.2.4" % "test",
      "org.scalacheck" %% "scalacheck" % "1.12.4" % "test"
    )
  )
  .settings(commonSettings ++ goodWarningSettings ++ Revolver.settings ++ ionrollerScalariformSettings)
  .dependsOn(core)

