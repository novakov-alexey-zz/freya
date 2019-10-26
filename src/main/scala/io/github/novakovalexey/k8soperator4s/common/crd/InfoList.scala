package io.github.novakovalexey.k8soperator4s.common.crd

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.fabric8.kubernetes.client.CustomResourceList
import io.fabric8.kubernetes.internal.KubernetesDeserializer

@JsonDeserialize(using = classOf[KubernetesDeserializer]) class InfoList[V] extends CustomResourceList[InfoClass[V]] {}
