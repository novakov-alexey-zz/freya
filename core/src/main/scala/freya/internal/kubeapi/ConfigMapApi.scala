package freya.internal.kubeapi

import freya.K8sNamespace
import freya.K8sNamespace.AllNamespaces
import io.fabric8.kubernetes.api.model.{ConfigMap, ConfigMapList}
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.{FilterWatchListDeletable, FilterWatchListMultiDeletable}

import scala.jdk.CollectionConverters._

private[freya] class ConfigMapApi(client: KubernetesClient) {
  type FilteredN = FilterWatchListMultiDeletable[ConfigMap, ConfigMapList]
  type Filtered = FilterWatchListDeletable[ConfigMap, ConfigMapList]

  def in(ns: K8sNamespace): FilteredN = {
    val _cms = client.configMaps
    if (AllNamespaces == ns) _cms.inAnyNamespace
    else _cms.inNamespace(ns.value)
  }

  def list(cms: FilteredN, labels: Map[String, String]): List[ConfigMap] =
    cms.withLabels(labels.asJava).list.getItems.asScala.toList

  def select(cms: FilteredN, labels: Map[String, String]): Filtered =
    cms.withLabels(labels.asJava)
}
