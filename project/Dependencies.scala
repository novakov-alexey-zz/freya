import sbt._

object Dependencies extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements

  object autoImport {

    object DependenciesVersion {
      val betterMonadicVersion             = "0.3.1"
      val catsVersion                      = "3.1.1"
      val circeVersion                     = "0.14.1"
      val circeYamlVersion                 = "0.14.0"
      val circeExtrasVersion               = "0.12.2"
      val fabric8K8sVersion                = "5.5.0"
      val jacksonScalaVersion              = "2.12.3"
      val jacksonJsonSchemaV               = "1.0.39"
      val logbackClassicVersion            = "1.3.0-alpha4"
      val scalaLoggingVersion              = "3.9.4"
      val scalaTestVersion                 = "3.2.9"
      val scalaTestCheckVersion            = "3.1.0.0-RC2"
      val scalaCheckVersion                = "1.15.4"
      val scalaJsonSchemaV                 = "0.2.3"
    }

    import DependenciesVersion._

    val catsEffect               = "org.typelevel"             %% "cats-effect"                % catsVersion
    val circeCore                = "io.circe"                  %% "circe-core"                 % circeVersion
    val circeParser              = "io.circe"                  %% "circe-parser"               % circeVersion
    val circeYaml                = "io.circe"                  %% "circe-yaml"                 % circeYamlVersion
    val circeGeneric             = "io.circe"                  %% "circe-generic"              % circeVersion
    val circeExtra               = "io.circe"                  %% "circe-generic-extras"       % circeExtrasVersion
    val k8sClient                = "io.fabric8"                % "kubernetes-client"           % fabric8K8sVersion
    val k8sModel                 = "io.fabric8"                % "kubernetes-model"            % fabric8K8sVersion
    val k8sServerMock            = "io.fabric8"                % "kubernetes-server-mock"      % fabric8K8sVersion
    val logbackClassic           = "ch.qos.logback"            %   "logback-classic"           % logbackClassicVersion
    val scalaLogging             = "com.typesafe.scala-logging" %% "scala-logging"             % scalaLoggingVersion
    val betterMonadicFor         = "com.olegpy"                 %% "better-monadic-for"        % betterMonadicVersion
    val jacksonScala             = "com.fasterxml.jackson.module" %% "jackson-module-scala"    % jacksonScalaVersion
    val jacksonDataFormat        = "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % jacksonScalaVersion
    val jacksonJsonSchema        = "com.kjetland"               %% "mbknor-jackson-jsonschema" % jacksonJsonSchemaV
    val scalaTest                = "org.scalatest"             %%  "scalatest"                 % scalaTestVersion
    val scalaTestCheck           = "org.scalatestplus"         %% "scalatestplus-scalacheck"   % scalaTestCheckVersion
    val scalaCheck               = "org.scalacheck"            %% "scalacheck"                 % scalaCheckVersion
    val scalaJsonSchema          = "com.github.andyglow"       %% "scala-jsonschema-api"       % scalaJsonSchemaV
  }
}
