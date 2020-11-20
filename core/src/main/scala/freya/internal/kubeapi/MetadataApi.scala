package freya.internal.kubeapi

import freya.models.Metadata
import io.fabric8.kubernetes.api.model.ObjectMeta

import scala.jdk.CollectionConverters.MapHasAsScala

private[freya] object MetadataApi {

  def translate(meta: ObjectMeta): Metadata =
    Metadata(meta.getName, meta.getNamespace, meta.getLabels.asScala.toMap, meta.getResourceVersion, meta.getUid)
}
