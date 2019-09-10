name := "k8s-operator4s"

version := "0.1"

scalaVersion := "2.13.0"

libraryDependencies ++= Seq(
  "io.fabric8" % "kubernetes-client" % "4.4.2",
  "io.fabric8" % "kubernetes-model" % "4.4.2",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"
)