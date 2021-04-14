package freya.watcher

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.node.ObjectNode
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Version}

class StringPropertyDeserializer extends StdDeserializer[StringProperty](classOf[StringProperty]) {
  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): StringProperty = {
    val node = jp.getCodec.readTree[ObjectNode](jp)
    StringProperty(node.toString)
  }
}

@JsonDeserialize(using = classOf[StringPropertyDeserializer])
final case class StringProperty(value: String)

@Group("placeholder_group")
@Version("placeholder_version")
class AnyCustomResource extends CustomResource[StringProperty, StringProperty]
