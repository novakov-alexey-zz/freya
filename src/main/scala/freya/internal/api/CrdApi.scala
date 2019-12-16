package freya.internal.api

import java.lang

import freya.K8sNamespace
import freya.K8sNamespace.AllNamespaces
import freya.internal.crd.{SpecDoneable, SpecList}
import freya.watcher.SpecClass
import io.fabric8.kubernetes.api.model.apiextensions.{CustomResourceDefinition, CustomResourceDefinitionBuilder, CustomResourceDefinitionFluent}
import io.fabric8.kubernetes.client.dsl.FilterWatchListMultiDeletable
import io.fabric8.kubernetes.client.{KubernetesClient, Watch, Watcher}

import scala.jdk.CollectionConverters._

object CrdApi {
  val ApiVersion = "apiextensions.k8s.io/v1beta1" //later: replace v1beta1 with v1

  def list(client: KubernetesClient): List[CustomResourceDefinition] =
    client.customResourceDefinitions.list.getItems.asScala.toList

  def createOrReplace(client: KubernetesClient, crd: CustomResourceDefinition): CustomResourceDefinition =
    client.customResourceDefinitions.createOrReplace(crd)

  def getCrdBuilder(
    prefix: String,
    kind: String,
    shortNames: List[String],
    pluralName: String
  ): CustomResourceDefinitionFluent.SpecNested[CustomResourceDefinitionBuilder] = {

    val shortNamesLower = shortNames.map(_.toLowerCase())

    new CustomResourceDefinitionBuilder()
      .withApiVersion(ApiVersion)
      .withNewMetadata
      .withName(s"$pluralName.$prefix")
      .endMetadata
      .withNewSpec
      .withNewNames
      .withKind(kind)
      .withPlural(pluralName)
      .withShortNames(shortNamesLower: _*)
      .endNames
      .withGroup(prefix)
      .withVersion("v1")
      .withScope("Namespaced")
      .withPreserveUnknownFields(false)
  }
}

class CrdApi(client: KubernetesClient) {
  type Filtered[T] =
    FilterWatchListMultiDeletable[SpecClass, SpecList, lang.Boolean, Watch, Watcher[SpecClass]]

  def in[T](ns: K8sNamespace, crd: CustomResourceDefinition): Filtered[T] = {
    val _crds = client.customResources(crd, classOf[SpecClass], classOf[SpecList], classOf[SpecDoneable])
    if (AllNamespaces == ns) _crds.inAnyNamespace else _crds.inNamespace(ns.value)
  }

  def list[T](crds: Filtered[T]): List[SpecClass] =
    crds.list().getItems.asScala.toList

}
