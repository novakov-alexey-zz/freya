ThisBuild / name := "freya"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.1"
ThisBuild / organization := "io.github.novakov-alexey"
Test / fork := true

lazy val `freya` = (project in file(".")).settings(
  addCompilerPlugin(betterMonadicFor),
  libraryDependencies ++= Seq(
        k8sClient,
        k8sModel,
        k8sServerMock % Test,
        catsEffect,
        scalaLogging,
        jacksonScala,
        scalaTest % Test,
        scalaCheck % Test,
        scalaTestCheck % Test,
        logbackClassic % Test,
        jacksonJsonSchema % Test,
        scalaJsonSchema % Test
      )
)

lazy val generate = taskKey[Unit]("Generate JSON Schema")
generate := (runMain in Test).toTask("freya.ScalaJsonSchema").value
