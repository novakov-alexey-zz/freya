package io.github.novakovalexey.k8soperator.internal.crd

import cats.effect.Sync
import cats.implicits._
import com.fasterxml.jackson.databind.DeserializationFeature
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apiextensions._
import io.fabric8.kubernetes.client.utils.Serialization
import io.fabric8.kubernetes.client.{CustomResourceList, KubernetesClient, KubernetesClientException}
import io.fabric8.kubernetes.internal.KubernetesDeserializer
import io.github.novakovalexey.k8soperator.AdditionalPrinterColumn
import io.github.novakovalexey.k8soperator.watcher.InfoClass

import scala.jdk.CollectionConverters._

private[k8soperator] object CrdDeployer extends LazyLogging {

  def initCrds[F[_]: Sync, T](
    client: KubernetesClient,
    apiPrefix: String,
    kind: String,
    shortNames: List[String],
    pluralName: String,
    additionalPrinterColumns: List[AdditionalPrinterColumn],
    infoClass: Class[T],
    isOpenshift: Option[Boolean]
  ): F[CustomResourceDefinition] =
    for {
      _ <- Sync[F].delay(Serialization.jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false))

      crds <- Sync[F].delay(
        client.customResourceDefinitions.list.getItems.asScala.toList
          .filter(p => kind == p.getSpec.getNames.getKind && apiPrefix == p.getSpec.getGroup)
      )

      crd <- crds match {
        case h :: _ =>
          Sync[F].delay(
            logger
              .info(s"CustomResourceDefinition for $kind has been found in the K8s, so we are skipping the creation.")
          ) *>
              h.pure[F]
        case Nil =>
          createCrd[F, T](
            client,
            apiPrefix,
            kind,
            shortNames,
            pluralName,
            additionalPrinterColumns,
            infoClass,
            isOpenshift
          )
      }

      _ <- Sync[F].delay {
        // register the new crd for json serialization
        val apiVersion = s"$apiPrefix/${crd.getSpec.getVersion}"
        KubernetesDeserializer.registerCustomKind(apiVersion, kind, classOf[InfoClass[_]])
        KubernetesDeserializer.registerCustomKind(
          apiVersion,
          s"${kind}List",
          classOf[CustomResourceList[_ <: HasMetadata]]
        )
      }
    } yield crd

  private def createCrd[F[_]: Sync, T](
    client: KubernetesClient,
    apiPrefix: String,
    kind: String,
    shortNames: List[String],
    pluralName: String,
    additionalPrinterColumns: List[AdditionalPrinterColumn],
    infoClass: Class[T],
    isOpenshift: Option[Boolean]
  ) =
    for {
      _ <- Sync[F].delay(logger.info(s"Creating CustomResourceDefinition for $kind."))
      jsonSchema <- Sync[F].delay(JSONSchemaReader.readSchema(infoClass))

      builder = {
        val crdBuilder = jsonSchema match {
          case Some(s) =>
            val schema = removeDefaultValues(s)
            getCRDBuilder(apiPrefix, kind, shortNames, pluralName).withNewValidation
              .withNewOpenAPIV3SchemaLike(schema)
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

      crd <- Sync[F].delay {
        val crd = builder.endSpec.build
        // https://github.com/fabric8io/kubernetes-client/issues/1486
        jsonSchema.foreach(_ => crd.getSpec.getValidation.getOpenAPIV3Schema.setDependencies(null))
        client.customResourceDefinitions.createOrReplace(crd)
        crd
      }.recover {
        case _: KubernetesClientException =>
          // old version of K8s/OpenShift -> don't use schema validation
          logger.warn(
            "Consider upgrading the {}. Your version doesn't support schema validation for custom resources.",
            if (isOpenshift.contains(true)) "OpenShift"
            else "Kubernetes"
          )
          val crd = getCRDBuilder(apiPrefix, kind, shortNames, pluralName).endSpec.build
          client.customResourceDefinitions.createOrReplace(crd)
          crd
      }
    } yield crd

  private def removeDefaultValues(schema: JSONSchemaProps): JSONSchemaProps =
    schema match {
      case null => schema
      case _ =>
        val newSchema = new JSONSchemaPropsBuilder(schema).build()
        newSchema.setDefault(null)
        if (null != newSchema.getProperties) {
          for (prop <- newSchema.getProperties.values.asScala) {
            removeDefaultValues(prop)
          }
        }
        newSchema
    }

  private def getCRDBuilder(
    prefix: String,
    entityName: String,
    shortNames: List[String],
    pluralName: String
  ): CustomResourceDefinitionFluent.SpecNested[CustomResourceDefinitionBuilder] = {

    val shortNamesLower = shortNames.map(_.toLowerCase())

    new CustomResourceDefinitionBuilder()
      .withApiVersion("apiextensions.k8s.io/v1beta1")
      .withNewMetadata
      .withName(s"$pluralName.$prefix")
      .endMetadata
      .withNewSpec
      .withNewNames
      .withKind(entityName)
      .withPlural(pluralName)
      .withShortNames(shortNamesLower: _*)
      .endNames
      .withGroup(prefix)
      .withVersion("v1")
      .withScope("Namespaced")
  }
}
