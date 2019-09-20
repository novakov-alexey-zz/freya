ThisBuild / name := "k8s-operator4s"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.1"
ThisBuild / organization := "io.github.novakov-alexey"

val fabric8K8sVer = "4.4.2"

lazy val `k8s-operator4s-core` = project.settings(
  libraryDependencies ++= Seq(
    "io.fabric8" % "kubernetes-client" % fabric8K8sVer,
    "io.fabric8" % "kubernetes-model" % fabric8K8sVer,
    scalaLogging,
    logbackClassic,
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.10.0.pr2"
  )
)

lazy val `k8s-operator4s` = project
  .in(file("."))
  .aggregate(`k8s-operator4s-core`)