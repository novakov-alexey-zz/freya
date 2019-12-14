package freya.internal.api

import java.lang

import io.fabric8.kubernetes.api.model.apiextensions.{CustomResourceDefinition, CustomResourceDefinitionBuilder, CustomResourceDefinitionFluent}
import io.fabric8.kubernetes.client.dsl.FilterWatchListMultiDeletable
import io.fabric8.kubernetes.client.{KubernetesClient, Watch, Watcher}
import freya.internal.crd.{InfoClassDoneable, InfoList}
import freya.watcher.InfoClass
import freya.{AllNamespaces, K8sNamespace}

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
    FilterWatchListMultiDeletable[InfoClass[T], InfoList[T], lang.Boolean, Watch, Watcher[InfoClass[T]]]

  def in[T](ns: K8sNamespace, crd: CustomResourceDefinition): Filtered[T] = {
    val _crds = client.customResources(crd, classOf[InfoClass[T]], classOf[InfoList[T]], classOf[InfoClassDoneable[T]])
    if (AllNamespaces == ns) _crds.inAnyNamespace else _crds.inNamespace(ns.value)
  }

  def list[T](crds: Filtered[T]): List[InfoClass[T]] =
    crds.list().getItems.asScala.toList

}
