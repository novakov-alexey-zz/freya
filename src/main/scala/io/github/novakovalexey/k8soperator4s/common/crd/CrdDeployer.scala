package io.github.novakovalexey.k8soperator4s.common.crd

import com.fasterxml.jackson.databind.DeserializationFeature
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apiextensions.{CustomResourceDefinition, CustomResourceDefinitionBuilder, JSONSchemaProps}
import io.fabric8.kubernetes.client.utils.Serialization
import io.fabric8.kubernetes.client.{CustomResourceList, KubernetesClient, KubernetesClientException}
import io.fabric8.kubernetes.internal.KubernetesDeserializer
import io.github.novakovalexey.k8soperator4s.common.{EntityInfo, JSONSchemaReader}

import scala.jdk.CollectionConverters._

class CrdDeployer extends LazyLogging {

  def initCrds(
    client: KubernetesClient,
    prefix: String,
    entityName: String,
    shortNames: Array[String],
    pluralName: String,
    additionalPrinterColumnNames: Array[String],
    additionalPrinterColumnPaths: Array[String],
    additionalPrinterColumnTypes: Array[String],
    infoClass: Class[_ <: EntityInfo],
    isOpenshift: Boolean
  ): CustomResourceDefinition = {
    val newPrefix = prefix.substring(0, prefix.length - 1) //TODO: why last character is skipped?
    Serialization.jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    val crds = client.customResourceDefinitions.list.getItems.asScala.toList
      .filter(p => entityName == p.getSpec.getNames.getKind && newPrefix == p.getSpec.getGroup)

    val crdToReturn = crds match {
      case h :: _ =>
        logger.info(
          s"CustomResourceDefinition for $entityName has been found in the K8s, so we are skipping the creation."
        )
        h
      case _ =>
        logger.info(s"Creating CustomResourceDefinition for $entityName.")
        val schema = JSONSchemaReader.readSchema(infoClass)

        val builder = {
          val b = if (schema != null) {
            removeDefaultValues(schema)
            getCRDBuilder(newPrefix, entityName, shortNames, pluralName).withNewValidation
              .withNewOpenAPIV3SchemaLike(schema)
              .endOpenAPIV3Schema
              .endValidation
          } else getCRDBuilder(newPrefix, entityName, shortNames, pluralName)

          if (additionalPrinterColumnNames != null && additionalPrinterColumnNames.length > 0) {
            additionalPrinterColumnNames.indices.foldLeft(b) {
              case (acc, i) =>
                acc
                  .addNewAdditionalPrinterColumn()
                  .withName(additionalPrinterColumnNames(i))
                  .withJSONPath(additionalPrinterColumnPaths(i))
                  .endAdditionalPrinterColumn
            }
          } else b
        }

        try {
          val crd = builder.endSpec.build
          // https://github.com/fabric8io/kubernetes-client/issues/1486
          Option(schema).foreach(_ => crd.getSpec.getValidation.getOpenAPIV3Schema.setDependencies(null))
          client.customResourceDefinitions.createOrReplace(crd)
          crd
        } catch {
          case _: KubernetesClientException =>
            // old version of K8s/openshift -> don't use schema validation
            logger.warn(
              "Consider upgrading the {}. Your version doesn't support schema validation for custom resources.",
              if (isOpenshift) "OpenShift"
              else "Kubernetes"
            )
            val crd = getCRDBuilder(newPrefix, entityName, shortNames, pluralName).endSpec.build
            client.customResourceDefinitions.createOrReplace(crd)
            crd
        }
    }

    // register the new crd for json serialization
    KubernetesDeserializer.registerCustomKind(
      s"$newPrefix/${crdToReturn.getSpec.getVersion}#$entityName",
      classOf[InfoClass[_]]
    )
    KubernetesDeserializer.registerCustomKind(
      s"$newPrefix/${crdToReturn.getSpec.getVersion}#${entityName}List",
      classOf[CustomResourceList[_ <: HasMetadata]]
    )
    crdToReturn
  }

  private def removeDefaultValues(schema: JSONSchemaProps): Unit = {
    if (null == schema) return
    schema.setDefault(null)
    if (null != schema.getProperties) {
      for (prop <- schema.getProperties.values.asScala) {
        removeDefaultValues(prop)
      }
    }
  }

  private def getCRDBuilder(prefix: String, entityName: String, shortNames: Array[String], pluralName: String) = { // if no plural name is specified, try to make one by adding "s"
    // also, plural names must be all lowercase
    val plural = {
      if (pluralName.isEmpty) entityName + "s" else pluralName
    }.toLowerCase

    // short names must be all lowercase
    val shortNamesLower = shortNames.map(_.toLowerCase())
    new CustomResourceDefinitionBuilder()
      .withApiVersion("apiextensions.k8s.io/v1beta1")
      .withNewMetadata
      .withName(s"$plural.$prefix")
      .endMetadata
      .withNewSpec
      .withNewNames
      .withKind(entityName)
      .withPlural(plural)
      .withShortNames(shortNamesLower: _*)
      .endNames
      .withGroup(prefix)
      .withVersion("v1")
      .withScope("Namespaced")
  }
}
