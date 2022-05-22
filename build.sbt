import sbt.Keys.libraryDependencies

import scala.collection.Seq

ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.8"

val AkkaVersion = "2.6.19"
val AkkaHttpVersion = "10.2.9"

lazy val root = (project in file("."))
  .settings(
    name := "socks5snif",

    libraryDependencies ++= Seq(

      "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion  % Compile,
      "com.typesafe.akka" %% "akka-stream" % AkkaVersion  % Compile,
      "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion  % Compile
    )
  )
