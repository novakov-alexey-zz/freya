package freya.internal.crd

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import freya.watcher.InfoClass
import io.fabric8.kubernetes.client.CustomResourceList
import io.fabric8.kubernetes.internal.KubernetesDeserializer

@JsonDeserialize(using = classOf[KubernetesDeserializer]) private[freya] class InfoList[V]
    extends CustomResourceList[InfoClass[V]] {}
