package io.github.novakovalexey.k8soperator4s.common

final case class Metadata(name: String, namespace: String)
final case class AdditionalPrinterColumn(name: String, `type`: String, jsonPath: String)

object OperatorCfg {
  val SAME_NAMESPACE = "~"
  val ALL_NAMESPACES = "*"
}

sealed abstract class OperatorCfg[T](
  val forKind: Class[T],
  val prefix: String,
  val namespace: Namespaces = AllNamespaces,
  val customKind: Option[String] = None
) {
  def validate: Either[String, Unit] =
    (forKind, prefix) match {
      case (null, _) => Left("forKind must not be null")
      case (_, null) => Left("prefix must not be null")
      case (_, _) if prefix.isEmpty => Left("prefix must not be empty")
      case _ => Right(())
    }
}

final case class CrdConfig[T](
  override val forKind: Class[T],
  override val namespace: Namespaces,
  override val prefix: String,
  override val customKind: Option[String] = None,
  shortNames: List[String] = List.empty[String],
  pluralName: String = "",
  additionalPrinterColumns: List[AdditionalPrinterColumn] = List.empty
) extends OperatorCfg(forKind, prefix, namespace, customKind)

final case class ConfigMapConfig[T](
  override val forKind: Class[T],
  override val namespace: Namespaces,
  override val prefix: String,
  override val customKind: Option[String] = None,
) extends OperatorCfg(forKind, prefix, namespace, customKind)

sealed trait Namespaces {
  val value: String
}
case object AllNamespaces extends Namespaces {
  val value: String = OperatorCfg.ALL_NAMESPACES
}
case object SameNamespace extends Namespaces {
  val value: String = OperatorCfg.SAME_NAMESPACE
}

final case class Namespace(value: String) extends Namespaces {
  override def toString: String = value
}
