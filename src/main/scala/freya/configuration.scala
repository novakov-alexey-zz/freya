package freya

import freya.K8sNamespace.AllNamespaces

import scala.concurrent.duration.{FiniteDuration, _}
import scala.reflect.ClassTag

sealed trait Retry

object Retry {
  def nextDelay(delay: FiniteDuration, multiplier: Int): FiniteDuration =
    delay * multiplier.toLong

  final case class Times(maxRetries: Int = 1, delay: FiniteDuration = 1.second, multiplier: Int = 1) extends Retry
  final case class Infinite(minDelay: FiniteDuration = 1.second, maxDelay: FiniteDuration = 60.seconds) extends Retry
}

final case class Metadata(name: String, namespace: String, resourceVersion: String)
final case class AdditionalPrinterColumn(name: String, columnType: String, jsonPath: String)

sealed abstract class Configuration(
  val prefix: String,
  val namespace: K8sNamespace = AllNamespaces,
  val customKind: Option[String] = None,
  val checkK8sOnStartup: Boolean = false
) {
  def validate[T: ClassTag]: Either[String, Unit] =
    (Option(kindClass[T]), Option(prefix)) match {
      case (None, _) => Left("forKind must not be null")
      case (_, None) => Left("prefix must not be null")
      case (_, _) if prefix.isEmpty => Left("prefix must not be empty")
      case _ => Right(())
    }

  def getKind[T: ClassTag]: String =
    customKind.getOrElse(kindClass[T].getSimpleName)

  def kindClass[T: ClassTag]: Class[T] =
    implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]]
}

object Configuration {

  final case class CrdConfig(
    override val namespace: K8sNamespace,
    override val prefix: String,
    version: String = "v1",
    override val checkK8sOnStartup: Boolean = true,
    override val customKind: Option[String] = None,
    deployCrd: Boolean = true,
    shortNames: List[String] = List.empty[String],
    pluralName: String = "",
    additionalPrinterColumns: List[AdditionalPrinterColumn] = List.empty
  ) extends Configuration(prefix, namespace, customKind) {

    def kindPluralCaseInsensitive[T: ClassTag]: String = {
      if (pluralName.isEmpty) getKind + "s" else pluralName
    }.toLowerCase

    val apiVersion: String = s"$prefix/$version"
  }

  final case class ConfigMapConfig(
    override val namespace: K8sNamespace,
    override val prefix: String,
    override val checkK8sOnStartup: Boolean = true,
    override val customKind: Option[String] = None
  ) extends Configuration(prefix, namespace, customKind)

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
