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
  //TODO: return Either with error message
  def validate: Boolean = {
    val ok = forKind != null
    ok && prefix != null && !prefix.isEmpty
  }
}

final case class CrdConfig[T](
  override val forKind: Class[T],
  override val namespace: Namespaces,
  override val prefix: String,
  override val customKind: Option[String] = None,
  shortNames: List[String] = List.empty[String],
  pluralName: String = "",
  additionalPrinterColumns: List[AdditionalPrinterColumn]
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

final case class Namespace(value: String) extends Namespaces
