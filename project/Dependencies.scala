import sbt._

object Dependencies extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements

  object autoImport {

    object DependenciesVersion {
      val logbackClassicVersion            = "1.2.3"
      val scalaTestVersion                 = "3.0.5"
      val scalaLoggingVersion              = "3.9.2"
    }

    import DependenciesVersion._
    
    val logbackClassic           = "ch.qos.logback"            %   "logback-classic"           % logbackClassicVersion
    val scalaLogging             = "com.typesafe.scala-logging" %% "scala-logging"             % scalaLoggingVersion
  }
}
