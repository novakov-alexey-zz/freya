package io.github.novakovalexey.k8soperator.common.crd

import io.fabric8.kubernetes.api.builder.Function
import io.fabric8.kubernetes.client.CustomResourceDoneable

class InfoClassDoneable[S](val resource: InfoClass[S], val f: Function[InfoClass[S], InfoClass[S]])
    extends CustomResourceDoneable[InfoClass[S]](resource, f) {}
