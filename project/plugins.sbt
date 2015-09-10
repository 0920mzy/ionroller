// The Typesafe repository
resolvers ++= Seq(
  "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases",
  "Typesafe repository" at "https://repo.typesafe.com/typesafe/releases"
)

libraryDependencies  ++= Seq(
  "com.amazonaws" % "aws-java-sdk-s3" % "1.10.12",
  "org.scalaz" %% "scalaz-concurrent" % "7.1.3"
)

// Use the Play sbt plugin for Play projects
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.4.2")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.0.4")

addSbtPlugin("io.spray" % "sbt-revolver" % "0.7.2")

addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "0.6.0")

addSbtPlugin("com.gilt.sbt" % "sbt-newrelic" % "0.1.3")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.5.0")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.2.0")

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.4.0")
