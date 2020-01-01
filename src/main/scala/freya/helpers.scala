package freya

import cats.syntax.either._
import freya.Configuration.CrdConfig
import freya.internal.OperatorUtils
import freya.internal.api.{ConfigMapApi, CrdApi}
import freya.models.{Resource, ResourcesList}
import freya.resource.{ConfigMapParser, Labels}
import freya.watcher.SpecClass
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient

import scala.util.Try

trait CrdHelperMaker[F[_], T] {
  def make(context: CrdHelperContext[T]): CrdHelper[F, T]
}

object CrdHelperMaker {
  implicit def helper[F[_], T]: CrdHelperMaker[F, T] =
    (context: CrdHelperContext[T]) => new CrdHelper[F, T](context)
}

trait ConfigMapHelperMaker[F[_], T] {
  def make(context: ConfigMapHelperContext[T]): ConfigMapHelper[F, T]
}

object ConfigMapHelperMaker {
  implicit def helper[F[_], T]: ConfigMapHelperMaker[F, T] =
    (context: ConfigMapHelperContext[T]) => new ConfigMapHelper[F, T](context)
}

sealed abstract class AbstractHelper[F[_], T](val client: KubernetesClient, val cfg: Configuration[T]) {
  val kind: String = cfg.getKind
  val targetNamespace: K8sNamespace = OperatorUtils.targetNamespace(client.getNamespace, cfg.namespace)

  def currentResources: Either[Throwable, ResourcesList[T]]
}

final case class ConfigMapHelperContext[T](
  cfg: Configuration.ConfigMapConfig[T],
  client: KubernetesClient,
  isOpenShift: Option[Boolean],
  parser: ConfigMapParser
)

object ConfigMapHelper {
  def convertCm[T](kind: Class[T], parser: ConfigMapParser)(cm: ConfigMap): Resource[T] =
    parser.parseCM(kind, cm).leftMap(_ -> cm)
}

class ConfigMapHelper[F[_], T](context: ConfigMapHelperContext[T])
    extends AbstractHelper[F, T](context.client, context.cfg) {

  val selector: Map[String, String] = Map(Labels.forKind(cfg.getKind, cfg.prefix))
  private val cmApi = new ConfigMapApi(client)

  def currentResources: Either[Throwable, ResourcesList[T]] = {
    val maps = Try(cmApi.in(targetNamespace)).toEither
    maps.map { m =>
      cmApi
        .list(m, selector)
        .map(ConfigMapHelper.convertCm(cfg.forKind, context.parser)(_))
    }
  }
}

final case class CrdHelperContext[T](
  cfg: CrdConfig[T],
  client: KubernetesClient,
  isOpenShift: Option[Boolean],
  crd: CustomResourceDefinition,
  parser: CustomResourceParser
)

object CrdHelper {

  def convertCr[T](kind: Class[T], parser: CustomResourceParser)(specClass: SpecClass): Resource[T] =
    for {
      spec <- parser.parse(kind, specClass).leftMap(_ -> specClass)
      meta <- Right(Metadata(specClass.getMetadata.getName, specClass.getMetadata.getNamespace))
    } yield (spec, meta)
}

class CrdHelper[F[_], T](context: CrdHelperContext[T]) extends AbstractHelper[F, T](context.client, context.cfg) {
  private val crdApi = new CrdApi(client)

  def currentResources: Either[Throwable, ResourcesList[T]] = {
    val crs = Try(crdApi.in[T](targetNamespace, context.crd)).toEither

    crs.map { c =>
      crdApi
        .list(c)
        .map(CrdHelper.convertCr(cfg.forKind, context.parser)(_))
    }
  }
}
