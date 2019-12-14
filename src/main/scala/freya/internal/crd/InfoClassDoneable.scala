package freya.internal.crd

import freya.watcher.InfoClass
import io.fabric8.kubernetes.api.builder.Function
import io.fabric8.kubernetes.client.CustomResourceDoneable

private[freya] class InfoClassDoneable[T](val resource: InfoClass[T], val f: Function[InfoClass[T], InfoClass[T]])
    extends CustomResourceDoneable[InfoClass[T]](resource, f) {}
