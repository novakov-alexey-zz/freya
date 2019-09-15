package io.github.novakovalexey.k8soperator4s.common

case class Metadata(name: String, namespace: String)

trait Operator[T] {
  def onAdd(entity: T, namespace: String): Unit = ()

  def onDelete(entity: T, namespace: String): Unit = ()

  def onModify(entity: T, namespace: String): Unit = ()

  def onInit(): Unit = ()
}

object OperatorCfg {
  val SAME_NAMESPACE = "~"
  val ALL_NAMESPACES = "*"
}

sealed abstract class OperatorCfg[T](
  val forKind: Class[T],
  val prefix: String,
  val namespace: String = OperatorCfg.ALL_NAMESPACES,
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
  override val namespace: String,
  override val prefix: String,
  override val customKind: Option[String] = None,
  shortNames: List[String] = List.empty[String],
  pluralName: String = "",
  additionalPrinterColumnNames: List[String] = List.empty[String],
  additionalPrinterColumnPaths: List[String] = List.empty[String],
  additionalPrinterColumnTypes: List[String] = List.empty[String]
) extends OperatorCfg(forKind, namespace, prefix, customKind) {
  override def validate: Boolean =
    super.validate && additionalPrinterColumnNames == null || (additionalPrinterColumnPaths != null
    && (additionalPrinterColumnNames.length == additionalPrinterColumnPaths.length)
    && (additionalPrinterColumnTypes == null || additionalPrinterColumnNames.length == additionalPrinterColumnTypes.length))
}

final case class ConfigMapConfig[T](
  override val forKind: Class[T],
  override val namespace: String,
  override val prefix: String,
  override val customKind: Option[String] = None,
) extends OperatorCfg(forKind, namespace, prefix, customKind)
