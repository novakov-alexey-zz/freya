package freya.internal.api

import freya.Metadata
import io.fabric8.kubernetes.api.model.ObjectMeta

object MetadataApi {

  def getMetadata(meta: ObjectMeta): Metadata = Metadata(meta.getName, meta.getNamespace, meta.getResourceVersion)
}
