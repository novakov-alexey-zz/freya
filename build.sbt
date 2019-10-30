ThisBuild / name := "k8s-operator4s"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.1"
ThisBuild / organization := "io.github.novakov-alexey"

lazy val root = (project in file(".")).settings(
  addCompilerPlugin(betterMonadicFor),
  libraryDependencies ++= Seq(
    k8sClient,
    k8sModel,
    catsEffect,
    betterMonadicFor,
    fs2Core,
    //TODO: get rid of scala logging
    scalaLogging,
    //TODO: get rid of jackson library
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.10.0.pr2",
    logbackClassic % Test,
  )
)