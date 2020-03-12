package freya.internal.kubeapi

import java.lang

import freya.K8sNamespace
import freya.K8sNamespace.AllNamespaces
import io.fabric8.kubernetes.api.model.{ConfigMap, ConfigMapList}
import io.fabric8.kubernetes.client.dsl.{FilterWatchListDeletable, FilterWatchListMultiDeletable}
import io.fabric8.kubernetes.client.{KubernetesClient, Watch, Watcher}

import scala.jdk.CollectionConverters._

private[freya] class ConfigMapApi(client: KubernetesClient) {
  type FilteredN = FilterWatchListMultiDeletable[ConfigMap, ConfigMapList, lang.Boolean, Watch, Watcher[ConfigMap]]
  type Filtered = FilterWatchListDeletable[ConfigMap, ConfigMapList, lang.Boolean, Watch, Watcher[ConfigMap]]

  def in(ns: K8sNamespace): FilteredN = {
    val _cms = client.configMaps
    if (AllNamespaces == ns) _cms.inAnyNamespace
    else _cms.inNamespace(ns.value)
  }

  def list(cms: FilteredN, selector: Map[String, String]): List[ConfigMap] =
    cms.withLabels(selector.asJava).list.getItems.asScala.toList

  def select(cms: FilteredN, selector: (String, String)): Filtered =
    cms.withLabels(Map(selector).asJava)
}
