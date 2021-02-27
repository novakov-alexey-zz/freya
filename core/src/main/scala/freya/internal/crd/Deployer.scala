package freya.internal.crd

import cats.effect.Sync
import cats.implicits._
import com.fasterxml.jackson.databind.DeserializationFeature
import com.typesafe.scalalogging.LazyLogging
import freya.Configuration.CrdConfig
import freya.internal.AnsiColors._
import freya.internal.kubeapi.CrdApi
import freya.watcher.AnyCustomResource
import freya.{AdditionalPrinterColumn, JsonReader}
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1._
import io.fabric8.kubernetes.client.utils.Serialization
import io.fabric8.kubernetes.client.{CustomResourceList, KubernetesClient, KubernetesClientException}
import io.fabric8.kubernetes.internal.KubernetesDeserializer

import scala.jdk.CollectionConverters._

private[freya] object Deployer extends LazyLogging {

  def deployCrd[F[_]: Sync, T: JsonReader](
    client: KubernetesClient,
    cfg: CrdConfig,
    isOpenShift: Option[Boolean]
  ): F[CustomResourceDefinition] =
    for {
      _ <- Sync[F].delay(Serialization.jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false))

      kind = cfg.getKind[T]
      crds <- Sync[F].delay(
        CrdApi.list(client).filter(p => kind == p.getSpec.getNames.getKind && cfg.prefix == p.getSpec.getGroup)
      )

      crd <- crds match {
        case h :: _ =>
          Sync[F].delay(
            logger
              .info(s"CustomResourceDefinition for $kind has been found in the K8s, so we are skipping its creation.")
          ) *>
              h.pure[F]
        case Nil if cfg.deployCrd =>
          createCrd[F, T](client, cfg, isOpenShift)
        case _ =>
          Sync[F].raiseError[CustomResourceDefinition](
            new RuntimeException(s"CustomResourceDefinition for $kind no found. Auto-deploy is disabled.")
          )
      }

      _ <- Sync[F].delay {
        // register the new crd for json serialization
        val apiVersion = s"${cfg.prefix}/${crd.getSpec.getVersion}"
        KubernetesDeserializer.registerCustomKind(apiVersion, kind, classOf[AnyCustomResource])
        KubernetesDeserializer.registerCustomKind(
          apiVersion,
          s"${kind}List",
          classOf[CustomResourceList[_ <: HasMetadata]]
        )
      }
    } yield crd

  private def createCrd[F[_]: Sync, T: JsonReader](
    client: KubernetesClient,
    cfg: CrdConfig,
    isOpenshift: Option[Boolean]
  ) =
    for {
      _ <- Sync[F].delay(logger.info(s"Creating CustomResourceDefinition for ${cfg.getKind}."))
      jsonSchema <- Sync[F].delay(JSONSchemaReader.readSchema(cfg.getKind))
      (baseBuilder, builderWithSchema) = createBuilder(cfg, jsonSchema)
      crd <- Sync[F].delay {
        val crd = builderWithSchema.endSpec.build
        // https://github.com/fabric8io/kubernetes-client/issues/1486
        jsonSchema.foreach(_ => crd.getSpec.getValidation.getOpenAPIV3Schema.setDependencies(null))
        CrdApi.createOrReplace(client, crd)
        crd
      }.recover {
        case e: KubernetesClientException =>
          logger.error("Error when submitting CR definition", e)
          logger.warn(
            s"Consider upgrading the $re{}$xx. Probably, your version doesn't support schema validation for custom resources.",
            if (isOpenshift.contains(true)) "OpenShift"
            else "Kubernetes"
          )
          CrdApi.createOrReplace(client, baseBuilder.endSpec.build)
      }
    } yield crd

  private def createBuilder[F[_], T: JsonReader](cfg: CrdConfig, jsonSchema: Option[JSONSchemaProps]) = {
    val baseBuilder = CrdApi
      .getCrdBuilder(
        cfg.prefix,
        cfg.getKind[T],
        cfg.shortNames,
        cfg.kindPluralCaseInsensitive[T],
        cfg.version,
        CrdConfig.crdApiVersion
      )
      .withNewSubresources()
      .withStatus(new CustomResourceSubresourceStatusBuilder().build())
      .endSubresources()
    val builderWithSchema = {
      val crdBuilder = jsonSchema match {
        case Some(s) =>
          val schema = removeDefaultValues(s)
          baseBuilder.withNewValidation
            .withNewOpenAPIV3SchemaLike(schema)
            .endOpenAPIV3Schema
            .endValidation
        case None =>
          baseBuilder
      }
      addColumns[F, T](cfg.additionalPrinterColumns, crdBuilder)
    }
    (baseBuilder, builderWithSchema)
  }

  private def addColumns[F[_], T](
    additionalPrinterColumns: List[AdditionalPrinterColumn],
    crdBuilder: CustomResourceDefinitionFluent.SpecNested[CustomResourceDefinitionBuilder]
  ) = {
    additionalPrinterColumns match {
      case Nil => crdBuilder
      case _ :: _ =>
        additionalPrinterColumns.foldLeft(crdBuilder) {
          case (acc, c) =>
            acc
              .addNewAdditionalPrinterColumn()
              .withName(c.name)
              .withType(c.columnType)
              .withJSONPath(c.jsonPath)
              .endAdditionalPrinterColumn
        }
    }
  }

  private def removeDefaultValues(schema: JSONSchemaProps): JSONSchemaProps =
    Option(schema) match {
      case None => schema
      case Some(_) =>
        val newSchema = new JSONSchemaPropsBuilder(schema).build()
        newSchema.setDefault(null)
        Option(newSchema.getProperties).foreach { map =>
          for (prop <- map.values.asScala) {
            removeDefaultValues(prop)
          }
        }
        newSchema
    }
}
