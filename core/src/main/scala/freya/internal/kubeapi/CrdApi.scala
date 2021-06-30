package freya.internal.kubeapi

import com.typesafe.scalalogging.LazyLogging
import freya.K8sNamespace.AllNamespaces
import freya.internal.kubeapi.CrdApi.{statusUpdateJson, CustomResourceList, StatusUpdate}
import freya.models.Metadata
import freya.{JsonWriter, K8sNamespace}
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.{CustomResourceDefinition, CustomResourceDefinitionBuilder, CustomResourceDefinitionFluent}
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext
import io.fabric8.kubernetes.client.dsl.internal.RawCustomResourceOperationsImpl

import scala.jdk.CollectionConverters._
import scala.util.Try

object CrdApi {
  type CustomResourceList = List[AnyRef]

  final case class StatusUpdate[T](meta: Metadata, status: T)

  // TODO: abstract CRD to let a user to switch between v1beta and v1
  def list(client: KubernetesClient): List[CustomResourceDefinition] =
    client.apiextensions.v1beta1.customResourceDefinitions.list.getItems.asScala.toList

  def createOrReplace(client: KubernetesClient, crd: CustomResourceDefinition): CustomResourceDefinition =
    client.apiextensions.v1beta1.customResourceDefinitions.createOrReplace(crd)

  def getCrdBuilder(
    prefix: String,
    kind: String,
    shortNames: List[String],
    pluralName: String,
    version: String,
    crdApiVersion: String
  ): CustomResourceDefinitionFluent.SpecNested[CustomResourceDefinitionBuilder] = {

    val shortNamesLower = shortNames.map(_.toLowerCase())

    new CustomResourceDefinitionBuilder()
      .withApiVersion(crdApiVersion)
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
      .withScope("Namespaced") //TODO: extract scope to config
      .withPreserveUnknownFields(false) //TODO: extract to config
  }

  def statusUpdateJson[T: JsonWriter](
    crd: CustomResourceDefinition,
    su: StatusUpdate[T],
    lastVersion: Option[String]
  ): String = {
    val status = implicitly[JsonWriter[T]].toString(su.status)
    val kind = crd.getSpec.getNames.getKind
    val apiVersion = s"${crd.getSpec.getGroup}/${crd.getSpec.getVersion}"
    val resourceVersion = lastVersion.getOrElse(su.meta.resourceVersion)
    val name = su.meta.name
    s"""{"kind":"$kind","apiVersion":"$apiVersion","metadata":{"name":"$name","resourceVersion":"$resourceVersion"},"status":$status}"""
  }

  def toCrdContext(crd: CustomResourceDefinition): CustomResourceDefinitionContext =
    new CustomResourceDefinitionContext.Builder()
      .withGroup(crd.getSpec.getGroup)
      .withName(crd.getMetadata.getName)
      .withPlural(crd.getSpec.getNames.getPlural)
      .withScope(crd.getSpec.getScope)
      .withVersion(crd.getSpec.getVersion)
      .build()
}

private[freya] class CrdApi(client: KubernetesClient, crd: CustomResourceDefinition) extends LazyLogging {
  private lazy val context = CrdApi.toCrdContext(crd)
  private val AnyNamespace = null

  def listResources[T](labels: Map[String, String]): CustomResourceList =
    toItemList(customResourceOperation.list(AnyNamespace, labels.asJava).asScala.toMap)

  def rawResource: RawCustomResourceOperationsImpl =
    customResourceOperation

  def listResources(ns: K8sNamespace): CustomResourceList = {
    val rawCustomResource = customResourceOperation
    val map = if (AllNamespaces == ns) rawCustomResource.list() else rawCustomResource.list(ns.value)
    toItemList(map.asScala.toMap)
  }

  private def toItemList(map: Map[String, AnyRef]): CustomResourceList = {
    val items = map.get("items")
    items.collect { case i: java.util.ArrayList[_] => i.asScala.toList.asInstanceOf[List[AnyRef]] }
      .getOrElse(List.empty[AnyRef])
  }

  private def customResourceOperation =
    client.customResource(context)

  def updateStatus[T: JsonWriter](su: StatusUpdate[T]): Unit = {
    val resourceProperties = Try(
      client
        .customResource(context)
        .inNamespace(su.meta.namespace)
        .withName(su.meta.name)
        .get()
    ).toOption.map(_.asScala.toMap)

    val lastVersion = latestResourceVersion(resourceProperties)

    logger.debug(s"lastResourceVersion: $lastVersion for ${su.meta}")
    val json = statusUpdateJson(crd, su, lastVersion)
    logger.debug(s"Update status json: $json")

    client
      .customResource(context)
      .inNamespace(su.meta.namespace)
      .withName(su.meta.name)
      .updateStatus(json)
    ()
  }

  private def latestResourceVersion[T](properties: Option[Map[String, AnyRef]]) = {
    val maybeMetadata = properties
      .getOrElse(Map.empty[String, AnyRef])
      .get("metadata")

    logger.debug(s"custom resource metadata: $maybeMetadata")

    maybeMetadata.collect { case m: java.util.LinkedHashMap[_, _] =>
      m.asScala.toMap.asInstanceOf[Map[String, AnyRef]]
    }.getOrElse(Map.empty[String, AnyRef])
      .get("resourceVersion")
      .map(_.asInstanceOf[String])
  }
}
