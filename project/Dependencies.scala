import sbt._

object Dependencies extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements

  object autoImport {

    object DependenciesVersion {
      val catsVersion                          = "2.0.0"
      val logbackClassicVersion            = "1.2.3"
      val scalaLoggingVersion              = "3.9.2"
      val fabric8K8sVersion                    = "4.6.1"
      val fs2Version                       = "2.0.1"
      val betterMonadicVersion             = "0.3.1"
    }

    import DependenciesVersion._

    val catsEffect               = "org.typelevel"             %% "cats-effect"                % catsVersion
    val fs2Core                  = "co.fs2"                    %% "fs2-core"                   % fs2Version
    val k8sClient                = "io.fabric8"                % "kubernetes-client"           % fabric8K8sVersion
    val k8sModel                 = "io.fabric8"                % "kubernetes-model"            % fabric8K8sVersion
    val logbackClassic           = "ch.qos.logback"            %   "logback-classic"           % logbackClassicVersion
    val scalaLogging             = "com.typesafe.scala-logging" %% "scala-logging"             % scalaLoggingVersion
    val betterMonadicFor         = "com.olegpy"                 %% "better-monadic-for"        % betterMonadicVersion
  }
}
