import microsites.ExtraMdFileConfig
import sbt.url
import sbtrelease.ReleaseStateTransformations._

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / name := "freya"
ThisBuild / scalaVersion := "2.13.1"
ThisBuild / organization := "io.github.novakov-alexey"
ThisBuild / watchBeforeCommand := Watch.clearScreen

// Publishing config //////////////////////////////////////////////////////
//ThisBuild / publishTo := {
//  val nexus = "https://oss.sonatype.org/"
//  Some("releases".at(nexus + "service/local/staging/deploy/maven2"))
//}

ThisBuild / publishTo := sonatypePublishToBundle.value

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
//ThisBuild / publishTo := {
//  val nexus = "https://oss.sonatype.org/"
//  if (isSnapshot.value) Some("snapshots".at(nexus + "content/repositories/snapshots"))
//  else Some("releases".at(nexus + "service/local/staging/deploy/maven2"))
//}
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

lazy val freya = (project in file(".")).aggregate(`core`, `api`, `jackson`, `circe`).settings(skip in publish := true)

lazy val `api` = (project in file("api"))
  .settings(moduleName := "freya-api")

lazy val `core` = (project in file("core"))
  .settings(moduleName := "freya-core")
  .settings(
    addCompilerPlugin(betterMonadicFor),
    publishArtifact in Test := false,
    libraryDependencies ++= Seq(
          k8sClient,
          k8sModel,
          k8sServerMock % Test,
          catsEffect,
          scalaLogging,
          circeGeneric % Test,
          scalaTest % Test,
          scalaCheck % Test,
          scalaTestCheck % Test,
          logbackClassic % Test,
          jacksonJsonSchema % Test,
          scalaJsonSchema % Test
        ),
    git.useGitDescribe := true
  )
  .dependsOn(`api`, `jackson` % "test", `circe` % "test")
  .enablePlugins(GitVersioning)

lazy val `jackson` = (project in file("jackson"))
  .settings(moduleName := "freya-jackson", libraryDependencies ++= Seq(jacksonScala, jacksonDataFormat))
  .dependsOn(`api`)

lazy val `circe` = (project in file("circe"))
  .settings(moduleName := "freya-circe", libraryDependencies ++= Seq(circeCore, circeParser, circeYaml))
  .dependsOn(`api`)

lazy val docs = (project in file("docs"))
  .settings(moduleName := "docs")
  .settings(libraryDependencies += circeExtra)
  .settings(
    Compile / scalacOptions  -= "-Wunused:imports",
    publishArtifact := false,
    mdocIn := new File("docs/docs"),
    mdocVariables := Map(
          "VERSION" -> git.gitDescribedVersion.value.flatMap(_.split("-").headOption).getOrElse("<version>")
        ),
    micrositeExtraMdFilesOutput := new File("./docs/docs"),
    micrositeExtraMdFiles := Map(
          file("README.md") -> ExtraMdFileConfig(
                "index.md",
                "home",
                Map("title" -> "Home", "section" -> "home", "position" -> "1")
              )
        ),
    micrositeName := "Freya",
    micrositeAuthor := "Alexey Novakov",
    micrositeTwitterCreator := "@alexey_novakov",
    micrositeTwitter := "@freya-scala",
    micrositeGithubOwner := "novakov-alexey",
    micrositeGithubRepo := "freya",
    micrositeHighlightLanguages ++= Seq("yaml", "json", "yml"),
    micrositeBaseUrl := "/freya",
    micrositeUrl := "https://novakov-alexey.github.io/freya/",
    micrositeGitterChannelUrl := "freya-scala/community",
    micrositeDocumentationUrl := "/freya/docs",
    micrositeHighlightTheme := "atelier-sulphurpool-light",
    micrositeTheme := "pattern",
    micrositeDataDirectory := (resourceDirectory in Compile).value / "microsite" / "data",
    micrositePalette := Map(
          "brand-primary" -> "#3E92CC",
          "brand-secondary" -> "#0A2463",
          "brand-tertiary" -> "#070890",
          "gray-dark" -> "#3A3A3A",
          "gray" -> "#7B7B7E",
          "gray-light" -> "#E5E5E6",
          "gray-lighter" -> "#F4F3F4",
          "white-color" -> "#FFFFFF"
        )
  )
  .dependsOn(`core` % "compile->test")
  .enablePlugins(MicrositesPlugin)
  .enablePlugins(MdocPlugin)

lazy val generateSchema = taskKey[Unit]("Generate JSON Schema")
generateSchema := (runMain in Test).toTask("freya.ScalaJsonSchema").value
