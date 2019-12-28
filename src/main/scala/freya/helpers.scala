package freya

import cats.effect.Effect
import cats.syntax.either._
import freya.Configuration.CrdConfig
import freya.internal.OperatorUtils
import freya.internal.api.{ConfigMapApi, CrdApi}
import freya.models.{Resource, ResourcesList}
import freya.resource.{ConfigMapParser, CrdParser, Labels}
import freya.watcher.SpecClass
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient

import scala.annotation.unused

sealed abstract class AbstractHelper[F[_]: Effect, T](val client: KubernetesClient, val cfg: Configuration[T]) {
  val kind: String = cfg.getKind
  val targetNamespace: K8sNamespace = OperatorUtils.targetNamespace(client.getNamespace, cfg.namespace)
}

object ConfigMapHelper {
  def convertCm[T](kind: Class[T], parser: ConfigMapParser)(cm: ConfigMap): Resource[T] =
    parser.parseCM(kind, cm).leftMap(_ -> cm)
}

class ConfigMapHelper[F[_]: Effect, T](
  cfg: Configuration.ConfigMapConfig[T],
  client: KubernetesClient,
  @unused isOpenShift: Option[Boolean],
  parser: ConfigMapParser
) extends AbstractHelper[F, T](client, cfg) {

  val selector: Map[String, String] = Map(Labels.forKind(cfg.getKind, cfg.prefix))
  private val cmApi = new ConfigMapApi(client)

  def currentConfigMaps: ResourcesList[T] = {
    val maps = cmApi.in(targetNamespace)
    cmApi
      .list(maps, selector)
      .map(ConfigMapHelper.convertCm(cfg.forKind, parser)(_))
  }
}

object CrdHelper {

  def convertCr[T](kind: Class[T], parser: CrdParser)(specClass: SpecClass): Resource[T] =
    for {
      spec <- parser.parse(kind, specClass).leftMap(_ -> specClass)
      meta <- Right(Metadata(specClass.getMetadata.getName, specClass.getMetadata.getNamespace))
    } yield (spec, meta)
}

class CrdHelper[F[_]: Effect, T](
  cfg: CrdConfig[T],
  client: KubernetesClient,
  @unused val isOpenShift: Option[Boolean],
  val crd: CustomResourceDefinition,
  val parser: CrdParser
) extends AbstractHelper[F, T](client, cfg) {
  private val crdApi = new CrdApi(client)

  def currentResources: ResourcesList[T] = {
    val crs = crdApi.in[T](targetNamespace, crd)

    crdApi
      .list(crs)
      .map(CrdHelper.convertCr(cfg.forKind, parser)(_))
  }
}
