ThisBuild / name := "k8s-operator4s"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.1"
ThisBuild / organization := "io.github.novakov-alexey"

val fabric8K8sVer = "4.4.2"

lazy val `k8s-operator4s-core` = project.settings(
  libraryDependencies ++= Seq(
    k8sClient,
    k8sModel,
    scalaLogging,
    //TODO: get rid of jackson library
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.10.0.pr2",
    logbackClassic % Test,
  )
)

lazy val `k8s-operator4s` = project
  .in(file("."))
  .aggregate(`k8s-operator4s-core`)