package freya.internal.crd

import freya.watcher.SpecClass
import io.fabric8.kubernetes.api.builder.Function
import io.fabric8.kubernetes.client.CustomResourceDoneable

private[freya] class SpecDoneable(val resource: SpecClass, val f: Function[SpecClass, SpecClass])
    extends CustomResourceDoneable[SpecClass](resource, f) {}
