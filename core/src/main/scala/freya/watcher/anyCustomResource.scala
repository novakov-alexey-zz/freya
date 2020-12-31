package freya.watcher

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.node.ObjectNode
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Version}

import java.lang.annotation.Annotation

class StringPropertyDeserializer extends StdDeserializer[StringProperty](classOf[StringProperty]) {
  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): StringProperty = {
    val node = jp.getCodec.readTree[ObjectNode](jp)
    StringProperty(node.toString)
  }
}

@JsonDeserialize(using = classOf[StringPropertyDeserializer])
final case class StringProperty(value: String)

@Group("fake")
@Version("fake")
class AnyCustomResource extends CustomResource[StringProperty, StringProperty] with HasMetadata {
  private var spec: StringProperty = _
  private var status: StringProperty = _

  override def getSpec: StringProperty = spec

  override def setSpec(spec: StringProperty): Unit =
    this.spec = spec

  override def getStatus: StringProperty = status

  override def setStatus(status: StringProperty): Unit =
    this.status = status

  override def toString: String =
    super.toString + s", spec: $spec, status: $status"
}

object CustomResourceAnnotations {

  def set(group: String, version: String): Unit = {
    val targetGroup = new Group {
      override def value: String = group

      override def annotationType(): Class[_ <: Annotation] = classOf[Group]
    }
    val targetVersion = new Version {
      override def value(): String = version

      override def annotationType(): Class[_ <: Annotation] = classOf[Version]
    }
    alterAnnotationValue(classOf[AnyCustomResource], classOf[Group], targetGroup)
    alterAnnotationValue(classOf[AnyCustomResource], classOf[Version], targetVersion)
  }

  private def alterAnnotationValue(
    targetClass: Class[_],
    targetAnnotation: Class[_ <: Annotation],
    targetValue: Annotation
  ) = {
    val method = classOf[Class[_]].getDeclaredMethod("annotationData")
    method.setAccessible(true)

    val annotationData = method.invoke(targetClass)
    val annotations = annotationData.getClass.getDeclaredField("annotations")
    annotations.setAccessible(true)

    val map = annotations.get(annotationData).asInstanceOf[java.util.Map[Class[_ <: Annotation], Annotation]]
    map.put(targetAnnotation, targetValue)
    ()
  }
}
