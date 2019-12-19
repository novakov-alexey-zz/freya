import sbt.url
import sbtrelease.ReleaseStateTransformations._

ThisBuild / name := "freya"
ThisBuild / scalaVersion := "2.13.1"
ThisBuild / organization := "io.github.novakov-alexey"

// Publishing config //////////////////////////////////////////////////////
ThisBuild /  publishTo := {
  val nexus = "https://oss.sonatype.org/"
  Some("releases".at(nexus + "service/local/staging/deploy/maven2"))
}

publishTo := sonatypePublishToBundle.value

ThisBuild / credentials += Credentials(Path.userHome / ".sbt" / "sonatype_credential")
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/novakov-alexey/freya"),
    "scm:git:git@github.com:novakov-alexey/freya.git"
  )
)
ThisBuild / developers := List(
  Developer(id = "novakov-alexey", name = "Alexey Novakov", email = "novakov.alex@gmail.com", url = url("https://github.com/novakov-alexey"))
)
ThisBuild / description := "Kubernetes Operator library for Scala"
ThisBuild / licenses := List("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / homepage := Some(url("https://github.com/novakov-alexey/freya"))
// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ =>
  false
}
ThisBuild / publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots".at(nexus + "content/repositories/snapshots"))
  else Some("releases".at(nexus + "service/local/staging/deploy/maven2"))
}
ThisBuild / publishMavenStyle := true
ThisBuild / organization := "io.github.novakov-alexey"

// Publishing config end /////////////////////////////////////////////////////////

Test / fork := true

releaseProcess := Seq.empty[ReleaseStep]
releaseProcess ++= (if (sys.env.contains("RELEASE_VERSION_BUMP"))
  Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    releaseStepCommandAndRemaining("publishSigned"),
    releaseStepCommand("sonatypeBundleRelease")
  )
else Seq.empty[ReleaseStep])
releaseProcess ++= (if (sys.env.contains("RELEASE_PUBLISH"))
  Seq[ReleaseStep](inquireVersions, setNextVersion, commitNextVersion, pushChanges)
else Seq.empty[ReleaseStep])

lazy val `freya` = (project in file(".")).settings(
  addCompilerPlugin(betterMonadicFor),
  publishArtifact in Test := false,
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
      ),
  mdocIn := new File("docs/README.md"),
  mdocOut := new File("README.md"),
  mdocVariables := Map("VERSION" -> git.gitDescribedVersion.value.flatMap(_.split("-").headOption).getOrElse("<version>")),
  git.useGitDescribe := true
).enablePlugins(MdocPlugin, GitVersioning)

lazy val generate = taskKey[Unit]("Generate JSON Schema")
generate := (runMain in Test).toTask("freya.ScalaJsonSchema").value
