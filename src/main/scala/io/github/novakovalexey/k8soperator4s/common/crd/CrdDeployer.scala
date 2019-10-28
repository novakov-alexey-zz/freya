package io.github.novakovalexey.k8soperator4s.common.crd

import com.fasterxml.jackson.databind.DeserializationFeature
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apiextensions.{CustomResourceDefinition, CustomResourceDefinitionBuilder, CustomResourceDefinitionFluent, JSONSchemaProps}
import io.fabric8.kubernetes.client.utils.Serialization
import io.fabric8.kubernetes.client.{CustomResourceList, KubernetesClient, KubernetesClientException}
import io.fabric8.kubernetes.internal.KubernetesDeserializer
import io.github.novakovalexey.k8soperator4s.AdditionalPrinterColumn
import io.github.novakovalexey.k8soperator4s.common.JSONSchemaReader

import scala.jdk.CollectionConverters._
import scala.util.Try

object CrdDeployer extends LazyLogging {

  def initCrds[T](
    client: KubernetesClient,
    apiPrefix: String,
    kind: String,
    shortNames: List[String],
    pluralName: String,
    additionalPrinterColumns: List[AdditionalPrinterColumn],
    infoClass: Class[T],
    isOpenshift: Boolean
  ): Either[Throwable, CustomResourceDefinition] = {
    Serialization.jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    val crds = client.customResourceDefinitions.list.getItems.asScala.toList
      .filter(p => kind == p.getSpec.getNames.getKind && apiPrefix == p.getSpec.getGroup)

    val crdOrError = crds match {
      case h :: _ =>
        logger.info(s"CustomResourceDefinition for $kind has been found in the K8s, so we are skipping the creation.")
        Right(h)
      case Nil =>
        createCrd[T](client, apiPrefix, kind, shortNames, pluralName, additionalPrinterColumns, infoClass, isOpenshift)
    }

    crdOrError.map { crd =>
      // register the new crd for json serialization
      KubernetesDeserializer.registerCustomKind(s"$apiPrefix/${crd.getSpec.getVersion}#$kind", classOf[InfoClass[_]])
      KubernetesDeserializer.registerCustomKind(
        s"$apiPrefix/${crd.getSpec.getVersion}#${kind}List",
        classOf[CustomResourceList[_ <: HasMetadata]]
      )
      crd
    }
  }

  private def createCrd[T](
    client: KubernetesClient,
    apiPrefix: String,
    kind: String,
    shortNames: List[String],
    pluralName: String,
    additionalPrinterColumns: List[AdditionalPrinterColumn],
    infoClass: Class[T],
    isOpenshift: Boolean
  ) = {
    logger.info(s"Creating CustomResourceDefinition for $kind.")
    val schema = JSONSchemaReader.readSchema(infoClass)

    val builder = {
      val crdBuilder = schema match {
        case Some(s) =>
          removeDefaultValues(s)
          getCRDBuilder(apiPrefix, kind, shortNames, pluralName).withNewValidation
            .withNewOpenAPIV3SchemaLike(s)
            .endOpenAPIV3Schema
            .endValidation
        case None =>
          getCRDBuilder(apiPrefix, kind, shortNames, pluralName)
      }

      additionalPrinterColumns match {
        case Nil => crdBuilder
        case _ :: _ =>
          additionalPrinterColumns.foldLeft(crdBuilder) {
            case (acc, c) =>
              acc
                .addNewAdditionalPrinterColumn()
                .withName(c.name)
                .withType(c.`type`)
                .withJSONPath(c.jsonPath)
                .endAdditionalPrinterColumn
          }
      }
    }

    Try {
      val crd = builder.endSpec.build
      // https://github.com/fabric8io/kubernetes-client/issues/1486
      schema.foreach(_ => crd.getSpec.getValidation.getOpenAPIV3Schema.setDependencies(null))
      client.customResourceDefinitions.createOrReplace(crd)
      crd
    }.recover {
      case _: KubernetesClientException =>
        // old version of K8s/openshift -> don't use schema validation
        logger.warn(
          "Consider upgrading the {}. Your version doesn't support schema validation for custom resources.",
          if (isOpenshift) "OpenShift"
          else "Kubernetes"
        )
        val crd = getCRDBuilder(apiPrefix, kind, shortNames, pluralName).endSpec.build
        client.customResourceDefinitions.createOrReplace(crd)
        crd
    }.toEither
  }

  private def removeDefaultValues(schema: JSONSchemaProps): Unit =
    schema match {
      case null => ()
      case _ =>
        schema.setDefault(null)
        if (null != schema.getProperties) {
          for (prop <- schema.getProperties.values.asScala) {
            removeDefaultValues(prop)
          }
        }
    }

  private def getCRDBuilder(
    prefix: String,
    entityName: String,
    shortNames: List[String],
    pluralName: String
  ): CustomResourceDefinitionFluent.SpecNested[CustomResourceDefinitionBuilder] = {

    val pluralLowerCase = {
      if (pluralName.isEmpty) entityName + "s" else pluralName
    }.toLowerCase

    val shortNamesLower = shortNames.map(_.toLowerCase())

    new CustomResourceDefinitionBuilder()
      .withApiVersion("apiextensions.k8s.io/v1beta1")
      .withNewMetadata
      .withName(s"$pluralLowerCase.$prefix")
      .endMetadata
      .withNewSpec
      .withNewNames
      .withKind(entityName)
      .withPlural(pluralLowerCase)
      .withShortNames(shortNamesLower: _*)
      .endNames
      .withGroup(prefix)
      .withVersion("v1")
      .withScope("Namespaced")
  }
}
