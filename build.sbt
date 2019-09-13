ThisBuild / name := "k8s-operator4s"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.0"

val fabric8K8sVer = "4.4.2"

lazy val `core` = project.settings(
  libraryDependencies ++= Seq(
    "io.fabric8" % "kubernetes-client" % fabric8K8sVer,
    "io.fabric8" % "kubernetes-model" % fabric8K8sVer,
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.10.0.pr2"
  )
)

lazy val `k8s-operator4s` = project
  .in(file("."))
  .aggregate(`core`)