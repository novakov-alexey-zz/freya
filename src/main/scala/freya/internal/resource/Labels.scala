package freya.internal.resource

object Labels {

  val OPERATOR_KIND_LABEL = "kind"

  def forKind(kind: String, prefix: String): Map[String, String] =
    Map(prefix + "/" + OPERATOR_KIND_LABEL -> kind)
}
