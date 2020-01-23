package freya

import cats.syntax.either._
import freya.Configuration.CrdConfig
import freya.internal.OperatorUtils
import freya.internal.api.{ConfigMapApi, CrdApi}
import freya.models.{CustomResource, Resource, ResourcesList}
import freya.resource.{ConfigMapParser, Labels}
import freya.watcher.AnyCustomResource
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient

import scala.reflect.ClassTag
import scala.util.Try

trait CrdHelperMaker[F[_], T, U] {
  def make(context: CrdHelperContext[T]): CrdHelper[F, T, U]
}

object CrdHelperMaker {
  implicit def helper[F[_], T, U: ClassTag]: CrdHelperMaker[F, T, U] =
    (context: CrdHelperContext[T]) => new CrdHelper[F, T, U](context)
}

trait ConfigMapHelperMaker[F[_], T] {
  def make(context: ConfigMapHelperContext[T]): ConfigMapHelper[F, T]
}

object ConfigMapHelperMaker {
  implicit def helper[F[_], T]: ConfigMapHelperMaker[F, T] =
    (context: ConfigMapHelperContext[T]) => new ConfigMapHelper[F, T](context)
}

sealed abstract class AbstractHelper[F[_], T, U](val client: KubernetesClient, val cfg: Configuration[T]) {
  val kind: String = cfg.getKind
  val targetNamespace: K8sNamespace = OperatorUtils.targetNamespace(client.getNamespace, cfg.namespace)

  def currentResources: Either[Throwable, ResourcesList[T, U]]
}

final case class ConfigMapHelperContext[T](
  cfg: Configuration.ConfigMapConfig[T],
  client: KubernetesClient,
  isOpenShift: Option[Boolean],
  parser: ConfigMapParser
)

object ConfigMapHelper {
  def convertCm[T](kind: Class[T], parser: ConfigMapParser)(cm: ConfigMap): Resource[T, Unit] =
    parser.parseCM(kind, cm).leftMap(_ -> cm).map {
      case (resource, meta) => CustomResource(resource, meta, None)
    }
}

class ConfigMapHelper[F[_], T](val context: ConfigMapHelperContext[T])
    extends AbstractHelper[F, T, Unit](context.client, context.cfg) {

  val selector: Map[String, String] = Map(Labels.forKind(cfg.getKind, cfg.prefix))
  private val cmApi = new ConfigMapApi(client)

  def currentResources: Either[Throwable, ResourcesList[T, Unit]] = {
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

  def convertCr[T, U: ClassTag](kind: Class[T], parser: CustomResourceParser)(
    resource: AnyCustomResource
  ): Resource[T, U] =
    for {
      (spec, status) <- parser
        .parse(kind, getStatusClass, resource)
        .leftMap(_ -> resource)
      meta <- Right(Metadata(resource.getMetadata.getName, resource.getMetadata.getNamespace))
    } yield CustomResource(spec, meta, status)

  private def getStatusClass[U: ClassTag, T]: Class[U] =
    implicitly[ClassTag[U]].runtimeClass.asInstanceOf[Class[U]]
}

class CrdHelper[F[_], T, U: ClassTag](val context: CrdHelperContext[T])
    extends AbstractHelper[F, T, U](context.client, context.cfg) {
  private val crdApi = new CrdApi(client)

  def currentResources: Either[Throwable, ResourcesList[T, U]] = {
    val crs = Try(crdApi.in[T](targetNamespace, context.crd)).toEither

    crs.map { c =>
      crdApi
        .list(c)
        .map(CrdHelper.convertCr(cfg.forKind, context.parser)(_))
    }
  }
}
