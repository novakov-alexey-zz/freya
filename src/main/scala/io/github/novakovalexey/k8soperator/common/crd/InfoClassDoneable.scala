package io.github.novakovalexey.k8soperator.common.crd

import io.fabric8.kubernetes.api.builder.Function
import io.fabric8.kubernetes.client.CustomResourceDoneable

class InfoClassDoneable[T](val resource: InfoClass[T], val f: Function[InfoClass[T], InfoClass[T]])
    extends CustomResourceDoneable[InfoClass[T]](resource, f) {}
