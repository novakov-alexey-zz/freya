ThisBuild / name := "k8s-operator4s"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.1"
ThisBuild / organization := "io.github.novakov-alexey"

lazy val `k8s-operator` = (project in file(".")).settings(
  addCompilerPlugin(betterMonadicFor),
  libraryDependencies ++= Seq(
    k8sClient,
    k8sModel,
    catsEffect,
    betterMonadicFor,
    fs2Core,
    scalaLogging,
    //TODO: get rid of jackson library
    jacksonScala,
    logbackClassic % Test,
  )
)