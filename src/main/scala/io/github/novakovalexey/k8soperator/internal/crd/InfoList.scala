package io.github.novakovalexey.k8soperator.internal.crd

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.fabric8.kubernetes.client.CustomResourceList
import io.fabric8.kubernetes.internal.KubernetesDeserializer
import io.github.novakovalexey.k8soperator.watcher.InfoClass

@JsonDeserialize(using = classOf[KubernetesDeserializer]) private[k8soperator] class InfoList[V]
    extends CustomResourceList[InfoClass[V]] {}
