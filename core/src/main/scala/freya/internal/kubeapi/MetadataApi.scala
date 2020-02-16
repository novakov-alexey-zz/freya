package freya.internal.kubeapi

import freya.Metadata
import io.fabric8.kubernetes.api.model.ObjectMeta

object MetadataApi {

  def translate(meta: ObjectMeta): Metadata =
    Metadata(meta.getName, meta.getNamespace, meta.getResourceVersion, meta.getUid)
}
