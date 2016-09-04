import com.typesafe.sbt.packager.docker._

name := "repo-wrangler"

organization := "stephanh"

scalaVersion := "2.11.8"

crossScalaVersions := Seq("2.11.8")

scalacOptions ++= Seq(
  "-deprecation",
  "-unchecked",
  "-Xlint",
  "-Ywarn-dead-code",
  "-Ywarn-value-discard",
  "-Ywarn-unused-import",
  "-feature",
  "-language:_",
  "-target:jvm-1.8"
)

resolvers += "Jenkins" at "https://repo.jenkins-ci.org/releases"

libraryDependencies ++= Seq(
  "org.scalaz"       %% "scalaz-core" % "7.2.5",
  "org.kohsuke"       % "github-api"  % "1.77",
  "com.github.scopt" %% "scopt"       % "3.5.0"
)

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)


dockerBaseImage := "openjdk:8-jre"
dockerCommands ++= List(
  Cmd("USER", "root"),
  Cmd("RUN", "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends git"),
  Cmd("USER", (daemonUser in Docker).value)
)

bintrayVcsUrl := Some("git@github.com:stephanh/repo-wrangler.git")
