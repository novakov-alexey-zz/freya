package freya.internal.crd

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import freya.watcher.SpecClass
import io.fabric8.kubernetes.client.CustomResourceList
import io.fabric8.kubernetes.internal.KubernetesDeserializer

@JsonDeserialize(using = classOf[KubernetesDeserializer]) private[freya] class SpecList
    extends CustomResourceList[SpecClass] {}
