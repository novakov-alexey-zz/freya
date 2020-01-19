package freya.internal.api

import java.lang

import freya.K8sNamespace
import freya.K8sNamespace.AllNamespaces
import freya.internal.crd.{AnyCrDoneable, AnyCrList}
import freya.watcher.AnyCustomResource
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
    FilterWatchListMultiDeletable[AnyCustomResource, AnyCrList, lang.Boolean, Watch, Watcher[AnyCustomResource]]

  def in[T](ns: K8sNamespace, crd: CustomResourceDefinition): Filtered[T] = {
    val _crs = client.customResources(crd, classOf[AnyCustomResource], classOf[AnyCrList], classOf[AnyCrDoneable])
    if (AllNamespaces == ns) _crs.inAnyNamespace else _crs.inNamespace(ns.value)
  }

  def list[T](crs: Filtered[T]): List[AnyCustomResource] =
    crs.list().getItems.asScala.toList

}
