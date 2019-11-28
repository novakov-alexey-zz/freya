import sbt._

object Dependencies extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements

  object autoImport {

    object DependenciesVersion {
      val betterMonadicVersion             = "0.3.1"
      val catsVersion                      = "2.0.0"
      val fabric8K8sVersion                = "4.6.3"
      val jacksonScalaVersion              = "2.10.0"
      val logbackClassicVersion            = "1.2.3"
      val scalaLoggingVersion              = "3.9.2"
      val scalaTestVersion                 = "3.0.8"
      val scalaCheckVersion                = "1.14.2"
    }

    import DependenciesVersion._

    val catsEffect               = "org.typelevel"             %% "cats-effect"                % catsVersion
    val k8sClient                = "io.fabric8"                % "kubernetes-client"           % fabric8K8sVersion
    val k8sModel                 = "io.fabric8"                % "kubernetes-model"            % fabric8K8sVersion
    val logbackClassic           = "ch.qos.logback"            %   "logback-classic"           % logbackClassicVersion
    val scalaLogging             = "com.typesafe.scala-logging" %% "scala-logging"             % scalaLoggingVersion
    val betterMonadicFor         = "com.olegpy"                 %% "better-monadic-for"        % betterMonadicVersion
    val jacksonScala             = "com.fasterxml.jackson.module" %% "jackson-module-scala"    % jacksonScalaVersion
    val scalaTest                = "org.scalatest"             %%  "scalatest"                 % scalaTestVersion % Test
    val scalaCheck               = "org.scalacheck"            %% "scalacheck"                 % scalaCheckVersion % Test
  }
}
