ThisBuild / name := "k8s-operator4s"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.1"
ThisBuild / organization := "io.github.novakov-alexey"

lazy val root = (project in file(".")).settings(
  libraryDependencies ++= Seq(
    k8sClient,
    k8sModel,
    catsEffect,
    fs2Core,
    //TODO: get rid of scala logging
    scalaLogging,
    //TODO: get rid of jackson library
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.10.0.pr2",
    logbackClassic % Test,
  )
)