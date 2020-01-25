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
  def make(context: CrdHelperContext): CrdHelper[F, T, U]
}

object CrdHelperMaker {
  implicit def helper[F[_], T: ClassTag, U: ClassTag]: CrdHelperMaker[F, T, U] =
    (context: CrdHelperContext) => new CrdHelper[F, T, U](context)
}

trait ConfigMapHelperMaker[F[_], T] {
  def make(context: ConfigMapHelperContext): ConfigMapHelper[F, T]
}

object ConfigMapHelperMaker {
  implicit def helper[F[_], T: ClassTag]: ConfigMapHelperMaker[F, T] =
    (context: ConfigMapHelperContext) => new ConfigMapHelper[F, T](context)
}

sealed abstract class AbstractHelper[F[_], T, U](val client: KubernetesClient, val cfg: Configuration) {
  val kind: String = cfg.getKind
  val targetNamespace: K8sNamespace = OperatorUtils.targetNamespace(client.getNamespace, cfg.namespace)

  def currentResources: Either[Throwable, ResourcesList[T, U]]
}

final case class ConfigMapHelperContext(
  cfg: Configuration.ConfigMapConfig,
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

class ConfigMapHelper[F[_], T: ClassTag](val context: ConfigMapHelperContext)
    extends AbstractHelper[F, T, Unit](context.client, context.cfg) {

  val selector: Map[String, String] = Map(Labels.forKind(cfg.getKind, cfg.prefix))
  private val cmApi = new ConfigMapApi(client)

  def currentResources: Either[Throwable, ResourcesList[T, Unit]] = {
    val maps = Try(cmApi.in(targetNamespace)).toEither
    maps.map { m =>
      cmApi
        .list(m, selector)
        .map(ConfigMapHelper.convertCm(cfg.kindClass[T], context.parser)(_))
    }
  }
}

final case class CrdHelperContext(
  cfg: CrdConfig,
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
      meta <- Right(getMetadata(resource))
    } yield CustomResource(spec, meta, status)

  private def getMetadata(resource: AnyCustomResource) =
    Metadata(resource.getMetadata.getName, resource.getMetadata.getNamespace, resource.getMetadata.getResourceVersion)

  private def getStatusClass[U: ClassTag, T]: Class[U] =
    implicitly[ClassTag[U]].runtimeClass.asInstanceOf[Class[U]]
}

class CrdHelper[F[_], T: ClassTag, U: ClassTag](val context: CrdHelperContext)
    extends AbstractHelper[F, T, U](context.client, context.cfg) {
  private val crdApi = new CrdApi(client)

  def currentResources: Either[Throwable, ResourcesList[T, U]] = {
    val crs = Try(crdApi.in[T](targetNamespace, context.crd)).toEither

    crs.map { c =>
      crdApi
        .list(c)
        .map(CrdHelper.convertCr(cfg.kindClass[T], context.parser)(_))
    }
  }
}
