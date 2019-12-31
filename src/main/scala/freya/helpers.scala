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

import scala.annotation.unused
import scala.util.Try

sealed abstract class AbstractHelper[F[_], T](val client: KubernetesClient, val cfg: Configuration[T]) {
  val kind: String = cfg.getKind
  val targetNamespace: K8sNamespace = OperatorUtils.targetNamespace(client.getNamespace, cfg.namespace)

  def currentResources: Either[Throwable, ResourcesList[T]]
}

object ConfigMapHelper {
  def convertCm[T](kind: Class[T], parser: ConfigMapParser)(cm: ConfigMap): Resource[T] =
    parser.parseCM(kind, cm).leftMap(_ -> cm)
}

class ConfigMapHelper[F[_], T](
  cfg: Configuration.ConfigMapConfig[T],
  client: KubernetesClient,
  @unused isOpenShift: Option[Boolean],
  parser: ConfigMapParser
) extends AbstractHelper[F, T](client, cfg) {

  val selector: Map[String, String] = Map(Labels.forKind(cfg.getKind, cfg.prefix))
  private val cmApi = new ConfigMapApi(client)

  def currentResources: Either[Throwable, ResourcesList[T]] = {
    val maps = Try(cmApi.in(targetNamespace)).toEither
    maps.map { m =>
      cmApi
        .list(m, selector)
        .map(ConfigMapHelper.convertCm(cfg.forKind, parser)(_))
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

class CrdHelper[F[_], T](
  context: CrdHelperContext[T]
) extends AbstractHelper[F, T](context.client, context.cfg) {
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
