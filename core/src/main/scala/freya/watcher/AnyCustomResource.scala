package freya.watcher

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.node.ObjectNode
import io.fabric8.kubernetes.client.CustomResource

class StringPropertyDeserializer extends StdDeserializer[StringProperty](classOf[StringProperty]) {
  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): StringProperty = {
    val node = jp.getCodec.readTree[ObjectNode](jp)
    StringProperty(node.toString)
  }
}

@JsonDeserialize(using = classOf[StringPropertyDeserializer])
final case class StringProperty(value: String)

class AnyCustomResource extends CustomResource {
  private var spec: StringProperty = _
  private var status: StringProperty = _

  def getSpec: StringProperty = spec

  def setSpec(spec: StringProperty): Unit =
    this.spec = spec

  def getStatus: StringProperty = status

  def setStatus(status: StringProperty): Unit =
    this.status = status

  override def toString: String =
    super.toString + s", spec: $spec, status: $status"
}
