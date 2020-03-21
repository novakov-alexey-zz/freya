package freya

import freya.K8sNamespace.AllNamespaces

import scala.concurrent.duration.{FiniteDuration, _}

sealed trait Retry

object Retry {
  def nextDelay(delay: FiniteDuration, multiplier: Int): FiniteDuration =
    delay * multiplier.toLong

  final case class Times(maxRetries: Int = 1, delay: FiniteDuration = 1.second, multiplier: Int = 1) extends Retry
  final case class Infinite(minDelay: FiniteDuration = 1.second, maxDelay: FiniteDuration = 60.seconds) extends Retry
}

final case class Metadata(name: String, namespace: String, resourceVersion: String, uid: String)
final case class AdditionalPrinterColumn(name: String, columnType: String, jsonPath: String)

sealed abstract class Configuration(
  val prefix: String,
  val namespace: K8sNamespace = AllNamespaces,
  val customKind: Option[String] = None,
  val namespaceQueueSize: Int,
  val checkK8sOnStartup: Boolean = false
) {
  def validate[T: JsonReader]: Either[String, Unit] =
    (Option(kindClass[T]), Option(prefix)) match {
      case (None, _) => Left("kind must not be null")
      case (_, None) => Left("prefix must not be null")
      case (_, _) if prefix.isEmpty => Left("prefix must not be empty")
      case _ => Right(())
    }

  def getKind[T: Reader]: String =
    customKind.getOrElse(kindClass[T].getSimpleName)

  def kindClass[T: Reader]: Class[T] =
    implicitly[Reader[T]].targetClass
}

object Configuration {

  final case class CrdConfig(
    override val namespace: K8sNamespace,
    override val prefix: String,
    override val namespaceQueueSize: Int = 10,
    version: String = "v1",
    override val checkK8sOnStartup: Boolean = true,
    override val customKind: Option[String] = None,
    deployCrd: Boolean = true,
    shortNames: List[String] = List.empty[String],
    pluralName: String = "",
    additionalPrinterColumns: List[AdditionalPrinterColumn] = List.empty,
    crdApiVersion: String = "v1beta1"
  ) extends Configuration(prefix, namespace, customKind, namespaceQueueSize) {

    def kindPluralCaseInsensitive[T: JsonReader]: String = {
      if (pluralName.isEmpty) getKind + "s" else pluralName
    }.toLowerCase

    val apiVersion: String = s"$prefix/$version"
  }

  final case class ConfigMapConfig(
    override val namespace: K8sNamespace,
    override val prefix: String,
    override val namespaceQueueSize: Int = 10,
    override val checkK8sOnStartup: Boolean = true,
    override val customKind: Option[String] = None
  ) extends Configuration(prefix, namespace, customKind, namespaceQueueSize)

}
sealed trait K8sNamespace {
  val value: String
}

object K8sNamespace {

  case object AllNamespaces extends K8sNamespace {
    val value: String = "all"
    override def toString: String = value
  }

  case object CurrentNamespace extends K8sNamespace {
    val value: String = "current"
    override def toString: String = value
  }

  final case class Namespace(value: String) extends K8sNamespace {
    override def toString: String = value
  }
}
