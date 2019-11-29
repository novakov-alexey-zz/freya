package io.github.novakovalexey.k8soperator.internal.crd

import io.fabric8.kubernetes.api.builder.Function
import io.fabric8.kubernetes.client.CustomResourceDoneable
import io.github.novakovalexey.k8soperator.watcher.InfoClass

private[k8soperator]  class InfoClassDoneable[T](val resource: InfoClass[T], val f: Function[InfoClass[T], InfoClass[T]])
    extends CustomResourceDoneable[InfoClass[T]](resource, f) {}
