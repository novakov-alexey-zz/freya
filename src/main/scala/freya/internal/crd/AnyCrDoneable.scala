package freya.internal.crd

import freya.watcher.AnyCustomResource
import io.fabric8.kubernetes.api.builder.Function
import io.fabric8.kubernetes.client.CustomResourceDoneable

private[freya] class AnyCrDoneable(val resource: AnyCustomResource, val f: Function[AnyCustomResource, AnyCustomResource])
    extends CustomResourceDoneable[AnyCustomResource](resource, f)
