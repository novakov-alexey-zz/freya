package freya.resource

object Labels {

  val OPERATOR_KIND_LABEL = "kind"

  def forKind(kind: String, prefix: String): (String, String) =
    prefix + "/" + OPERATOR_KIND_LABEL -> kind
}
