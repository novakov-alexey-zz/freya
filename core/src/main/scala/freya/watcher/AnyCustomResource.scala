package freya.watcher

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.annotation.{JsonDeserialize, JsonSerialize}
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.databind.{DeserializationContext, SerializerProvider}
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Version}

class StringPropertyDeserializer extends StdDeserializer[StringProperty](classOf[StringProperty]) {
  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): StringProperty = {
    val node = jp.getCodec.readTree[ObjectNode](jp)
    StringProperty(node.toString)
  }
}

class StringPropertySerializer extends StdSerializer[StringProperty](classOf[StringProperty]) {
  override def serialize(value: StringProperty, gen: JsonGenerator, provider: SerializerProvider): Unit =
    gen.writeRawValue(value.value)
}

@JsonDeserialize(using = classOf[StringPropertyDeserializer])
@JsonSerialize(using = classOf[StringPropertySerializer])
final case class StringProperty(value: String)

@Group("placeholder_group")
@Version("placeholder_version")
class AnyCustomResource extends CustomResource[StringProperty, StringProperty]
