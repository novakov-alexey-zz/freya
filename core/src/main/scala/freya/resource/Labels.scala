package freya.resource

object Labels {

  val OperatorKindLabel = "kind"

  def forKind(kind: String, prefix: String): (String, String) =
    prefix + "/" + OperatorKindLabel -> kind
}
