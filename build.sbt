import sbt.url
import sbtrelease.ReleaseStateTransformations._

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / name := "freya"
ThisBuild / scalaVersion := "2.13.1"
ThisBuild / organization := "io.github.novakov-alexey"

// Publishing config //////////////////////////////////////////////////////
ThisBuild / publishTo := {
  val nexus = "https://oss.sonatype.org/"
  Some("releases".at(nexus + "service/local/staging/deploy/maven2"))
}

publishTo := sonatypePublishToBundle.value

ThisBuild / credentials += Credentials(Path.userHome / ".sbt" / "sonatype_credential")
ThisBuild / scmInfo := Some(
  ScmInfo(url("https://github.com/novakov-alexey/freya"), "scm:git:git@github.com:novakov-alexey/freya.git")
)
ThisBuild / developers := List(
  Developer(
    id = "novakov-alexey",
    name = "Alexey Novakov",
    email = "novakov.alex@gmail.com",
    url = url("https://github.com/novakov-alexey")
  )
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

lazy val `freya` = (project in file("."))
  .settings(
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
    micrositeAuthor := "Alexey Novakov",
    micrositeTwitterCreator := "@alexey_novakov",
    micrositeGithubOwner := "novakov-alexey",
    micrositeGithubRepo := "freya",
//    micrositeGithubToken := sys.env.get("GITHUB_TOKEN"),
    micrositeHighlightLanguages ++= Seq("yaml", "json", "yml"),
    micrositeBaseUrl := "/freya",
    micrositeDocumentationUrl := "/freya/docs",
//    micrositePushSiteWith := GitHub4s,
    micrositePalette := Map(
          "brand-primary" -> "#E05236",
          "brand-secondary" -> "#3F3242",
          "brand-tertiary" -> "#2D232F",
          "gray-dark" -> "#453E46",
          "gray" -> "#837F84",
          "gray-light" -> "#E3E2E3",
          "gray-lighter" -> "#F4F3F4",
          "white-color" -> "#FFFFFF"
        ),
    micrositeExtraMdFilesOutput := mdocIn.value,
    ghpagesBranch := "master",
    git.useGitDescribe := true
  )
  .enablePlugins(GitVersioning)
  .enablePlugins(MicrositesPlugin)

lazy val `docs` = project
  .in(file("docs"))
  .settings(
//    mdocIn := new File("docs/docs"),
//    mdocOut := new File("README.md"),
    mdocVariables := Map(
          "VERSION" -> git.gitDescribedVersion.value.flatMap(_.split("-").headOption).getOrElse("<version>")
        )
  )
//  .dependsOn(`freya`)
  .enablePlugins(MdocPlugin)

lazy val generateSchema = taskKey[Unit]("Generate JSON Schema")
generateSchema := (runMain in Test).toTask("freya.ScalaJsonSchema").value
