import sbt._

object Dependencies extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements

  object autoImport {

    object DependenciesVersion {
      val logbackClassicVersion            = "1.2.3"
      val scalaLoggingVersion              = "3.9.2"
      val fabric8K8sVer                    = "4.4.2"
    }

    import DependenciesVersion._

    val k8sClient                = "io.fabric8"                % "kubernetes-client"           % fabric8K8sVer
    val k8sModel                 = "io.fabric8"                % "kubernetes-model"            % fabric8K8sVer
    val logbackClassic           = "ch.qos.logback"            %   "logback-classic"           % logbackClassicVersion
    val scalaLogging             = "com.typesafe.scala-logging" %% "scala-logging"             % scalaLoggingVersion
  }
}
