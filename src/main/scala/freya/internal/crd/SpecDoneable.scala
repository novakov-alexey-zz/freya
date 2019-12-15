package freya.internal.crd

import freya.watcher.SpecClass
import io.fabric8.kubernetes.api.builder.Function
import io.fabric8.kubernetes.client.CustomResourceDoneable

private[freya] class SpecDoneable[T](val resource: SpecClass[T], val f: Function[SpecClass[T], SpecClass[T]])
    extends CustomResourceDoneable[SpecClass[T]](resource, f) {}
