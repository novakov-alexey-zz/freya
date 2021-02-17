package freya.watcher

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Version}

import java.lang.annotation.Annotation

@Group("fake")
@Version("fake")
class AnyCustomResource extends CustomResource[String, String] with HasMetadata

object CustomResourceAnnotations {

  def set(group: String, version: String): Unit = {
    val targetGroup = new Group {
      override def value: String = group

      override def annotationType(): Class[_ <: Annotation] = classOf[Group]
    }
    val targetVersion = new Version {
      override def served() = true

      override def storage() = true

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
  ): Unit = {
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
