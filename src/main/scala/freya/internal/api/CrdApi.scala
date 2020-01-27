package freya.internal.api

import java.lang

import freya.K8sNamespace
import freya.K8sNamespace.{AllNamespaces, Namespace}
import freya.internal.api.CrdApi.Filtered
import freya.internal.crd.{AnyCrDoneable, AnyCrList}
import freya.models.CustomResource
import freya.watcher.AnyCustomResource
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.apiextensions.{CustomResourceDefinition, CustomResourceDefinitionBuilder, CustomResourceDefinitionFluent}
import io.fabric8.kubernetes.client.dsl.FilterWatchListMultiDeletable
import io.fabric8.kubernetes.client.{KubernetesClient, Watch, Watcher}

import scala.jdk.CollectionConverters._

object CrdApi {
  type Filtered[T] =
    FilterWatchListMultiDeletable[AnyCustomResource, AnyCrList, lang.Boolean, Watch, Watcher[AnyCustomResource]]

  val ApiVersion = "apiextensions.k8s.io/v1beta1" //later: replace v1beta1 with v1

  def list(client: KubernetesClient): List[CustomResourceDefinition] =
    client.customResourceDefinitions.list.getItems.asScala.toList

  def createOrReplace(client: KubernetesClient, crd: CustomResourceDefinition): CustomResourceDefinition =
    client.customResourceDefinitions.createOrReplace(crd)

  def getCrdBuilder(
    prefix: String,
    kind: String,
    shortNames: List[String],
    pluralName: String,
    version: String
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
      .withVersion(version)
      .withScope("Namespaced")
      .withPreserveUnknownFields(false)
  }

  def list[T](crs: Filtered[T]): List[AnyCustomResource] =
    crs.list().getItems.asScala.toList

  def resourceWithStatus[T, U](crd: CustomResourceDefinition, cr: CustomResource[T, U]): AnyCustomResource = {
    val anyCr = new AnyCustomResource
    anyCr.setKind(crd.getSpec.getNames.getKind)
    anyCr.setApiVersion(s"${crd.getSpec.getGroup}/${crd.getSpec.getVersion}")
    anyCr.setStatus(cr.status.asInstanceOf[AnyRef])
    anyCr.setMetadata(
      new ObjectMetaBuilder()
        .withName(cr.metadata.name)
        .withResourceVersion(cr.metadata.resourceVersion)
        .build()
    )
    anyCr
  }
}

class CrdApi(client: KubernetesClient) {
  def in[T](ns: K8sNamespace, crd: CustomResourceDefinition): Filtered[T] = {
    val _crs = client.customResources(crd, classOf[AnyCustomResource], classOf[AnyCrList], classOf[AnyCrDoneable])
    if (AllNamespaces == ns) _crs.inAnyNamespace else _crs.inNamespace(ns.value)
  }

  def updateStatus[T, U](crd: CustomResourceDefinition, resource: CustomResource[T, U]): AnyCustomResource =
    in(Namespace(resource.metadata.namespace), crd).updateStatus(CrdApi.resourceWithStatus(crd, resource))
}
