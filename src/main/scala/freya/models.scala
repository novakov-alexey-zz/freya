package freya

import scala.concurrent.duration.{FiniteDuration, _}

final case class Retry(times: Int = 1, delay: FiniteDuration = 1.second, multiplier: Int = 1)

final case class Metadata(name: String, namespace: String)

final case class AdditionalPrinterColumn(name: String, `type`: String, jsonPath: String)

sealed abstract class OperatorCfg[T](
  val forKind: Class[T],
  val prefix: String,
  val namespace: K8sNamespace = AllNamespaces,
  val customKind: Option[String] = None,
  val checkK8sOnStartup: Boolean = false
) {
  def validate: Either[String, Unit] =
    (Option(forKind), Option(prefix)) match {
      case (None, _) => Left("forKind must not be null")
      case (_, None) => Left("prefix must not be null")
      case (_, _) if prefix.isEmpty => Left("prefix must not be empty")
      case _ => Right(())
    }

  def getKind: String =
    customKind.getOrElse(forKind.getSimpleName)
}

final case class CrdConfig[T](
  override val forKind: Class[T],
  override val namespace: K8sNamespace,
  override val prefix: String,
  override val checkK8sOnStartup: Boolean = true,
  override val customKind: Option[String] = None,
  readOrCreateCrd: Boolean = false,
  shortNames: List[String] = List.empty[String],
  pluralName: String = "",
  additionalPrinterColumns: List[AdditionalPrinterColumn] = List.empty
) extends OperatorCfg(forKind, prefix, namespace, customKind) {

  def getPluralCaseInsensitive: String = {
    if (pluralName.isEmpty) getKind + "s" else pluralName
  }.toLowerCase
}

final case class ConfigMapConfig[T](
  override val forKind: Class[T],
  override val namespace: K8sNamespace,
  override val prefix: String,
  override val checkK8sOnStartup: Boolean = true,
  override val customKind: Option[String] = None
) extends OperatorCfg(forKind, prefix, namespace, customKind)

sealed trait K8sNamespace {
  val value: String
}
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
