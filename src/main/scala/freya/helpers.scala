package freya

import cats.effect.Effect
import cats.implicits._
import freya.OperatorCfg.Crd
import freya.internal.OperatorUtils
import freya.internal.api.{ConfigMapApi, CrdApi}
import freya.resource.{ConfigMapParser, CrdParser, Labels}
import freya.watcher.SpecClass
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient

import scala.annotation.unused

sealed abstract class AbstractHelper[F[_]: Effect, T](val client: KubernetesClient, val cfg: OperatorCfg[T]) {
  val kind: String = cfg.getKind
  val targetNamespace: K8sNamespace = OperatorUtils.targetNamespace(client.getNamespace, cfg.namespace)
}

object ConfigMapHelper {
  def convertCm[T](kind: Class[T], parser: ConfigMapParser)(cm: ConfigMap): Either[Throwable, (T, Metadata)] =
    parser.parseCM(kind, cm)
}

class ConfigMapHelper[F[_]: Effect, T](
  cfg: OperatorCfg.ConfigMap[T],
  client: KubernetesClient,
  @unused isOpenShift: Option[Boolean],
  parser: ConfigMapParser
) extends AbstractHelper[F, T](client, cfg) {

  val selector: (String, String) = Labels.forKind(cfg.getKind, cfg.prefix)
  private val cmApi = new ConfigMapApi(client)

  def currentConfigMaps: Either[List[Throwable], Map[Metadata, T]] = {
    val cms = cmApi.in(targetNamespace)

    cmApi
      .list(cms, selector)
      .map(ConfigMapHelper.convertCm(cfg.forKind, parser)(_).toValidatedNec)
      .sequence
      .map(_.map { case (entity, meta) => meta -> entity }.toMap)
      .toEither
      .leftMap(_.toList)
  }
}

object CrdHelper {

  def convertCr[T](kind: Class[T], parser: CrdParser)(info: SpecClass[T]): Either[Throwable, (T, Metadata)] =
    for {
      spec <- parser.parse(kind, info)
      meta <- Right(Metadata(info.getMetadata.getName, info.getMetadata.getNamespace))
    } yield (spec, meta)
}

class CrdHelper[F[_]: Effect, T](
  cfg: Crd[T],
  client: KubernetesClient,
  @unused val isOpenShift: Option[Boolean],
  val crd: CustomResourceDefinition,
  val parser: CrdParser
) extends AbstractHelper[F, T](client, cfg) {
  private val crdApi = new CrdApi(client)

  def currentResources: Either[List[Throwable], Map[Metadata, T]] = {
    val crds = crdApi.in[T](targetNamespace, crd)

    crdApi
      .list(crds)
      .map(CrdHelper.convertCr(cfg.forKind, parser)(_).toValidatedNec)
      .sequence
      .map(_.map { case (entity, meta) => meta -> entity }.toMap)
      .toEither
      .leftMap(_.toList)
  }
}
