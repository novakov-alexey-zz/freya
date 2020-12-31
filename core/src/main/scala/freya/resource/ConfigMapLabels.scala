package freya.resource

object ConfigMapLabels {

  val OperatorKindLabel = "kind"
  val OperatorVersionLabel = "version"

  def forKind(kind: String, prefix: String, version: String): Map[String, String] =
    Map(prefix + "/" + OperatorKindLabel -> kind, prefix + "/" + OperatorVersionLabel -> version)
}
