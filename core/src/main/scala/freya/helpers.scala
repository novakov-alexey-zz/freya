package freya

import cats.syntax.either._
import freya.Configuration.CrdConfig
import freya.CrdHelper.convertCr
import freya.K8sNamespace.AllNamespaces
import freya.internal.OperatorUtils
import freya.internal.kubeapi.CrdApi.CustomResourceList
import freya.internal.kubeapi.{ConfigMapApi, CrdApi}
import freya.models.{CustomResource, Metadata, Resource, ResourcesList}
import freya.resource.{ConfigMapLabels, ConfigMapParser}
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient

import scala.util.Try

trait CrdHelperMaker[F[_], T, U] {
  def make(context: CrdHelperContext): CrdHelper[F, T, U]
}

object CrdHelperMaker {
  implicit def helper[F[_], T: JsonReader, U: JsonReader]: CrdHelperMaker[F, T, U] =
    (context: CrdHelperContext) => new CrdHelper[F, T, U](context)
}

trait ConfigMapHelperMaker[F[_], T] {
  def make(context: ConfigMapHelperContext): ConfigMapHelper[F, T]
}

object ConfigMapHelperMaker {
  implicit def helper[F[_], T: YamlReader]: ConfigMapHelperMaker[F, T] =
    (context: ConfigMapHelperContext) => new ConfigMapHelper[F, T](context)
}

sealed abstract class ResourceHelper[F[_], T: Reader, U](val client: KubernetesClient, val cfg: Configuration) {
  val kind: String = cfg.getKind
  val targetNamespace: K8sNamespace = OperatorUtils.targetNamespace(client.getNamespace, cfg.namespace)

  def currentResources(namespace: K8sNamespace = targetNamespace): Either[Throwable, ResourcesList[T, U]]
  def currentResources(labels: Map[String, String]): Either[Throwable, ResourcesList[T, U]]
}

final case class ConfigMapHelperContext(
  cfg: Configuration.ConfigMapConfig,
  client: KubernetesClient,
  isOpenShift: Option[Boolean],
  parser: ConfigMapParser
)

object ConfigMapHelper {
  def convertCm[T: YamlReader](parser: ConfigMapParser)(cm: ConfigMap): Resource[T, Unit] =
    parser.parseCM(cm).map { case (resource, meta) =>
      CustomResource(Metadata.fromObjectMeta(meta), resource, None)
    }
}

class ConfigMapHelper[F[_], T: YamlReader](val context: ConfigMapHelperContext)
    extends ResourceHelper[F, T, Unit](context.client, context.cfg) {

  val selector: Map[String, String] = ConfigMapLabels.forKind(cfg.getKind, cfg.prefix, cfg.version)
  private val cmApi = new ConfigMapApi(client)

  def currentResources(namespace: K8sNamespace = targetNamespace): Either[Throwable, ResourcesList[T, Unit]] = {
    val resources = Try(cmApi.in(targetNamespace)).toEither
    resources.map { m =>
      cmApi
        .list(m, selector)
        .map(ConfigMapHelper.convertCm(context.parser)(_))
    }
  }

  def currentResources(labels: Map[String, String]): Either[Throwable, ResourcesList[T, Unit]] = {
    val resources = Try(cmApi.in(AllNamespaces)).toEither
    convert(resources, selector ++ labels)
  }

  private def convert(resources: Either[Throwable, cmApi.FilteredN], labels: Map[String, String]) = {
    resources.map { m =>
      cmApi
        .list(m, labels)
        .map(ConfigMapHelper.convertCm[T](context.parser)(_))
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
  def convertCr[T: JsonReader, U: JsonReader](parser: CustomResourceParser)(resource: AnyRef): Resource[T, U] =
    for {
      (spec, status, meta) <- parser
        .parse[T, U](resource)
        .leftMap { case (t, r) =>
          t -> r
        }
    } yield CustomResource(Metadata.fromObjectMeta(meta), spec, status)
}

class CrdHelper[F[_], T: JsonReader, U: JsonReader](val context: CrdHelperContext)
    extends ResourceHelper[F, T, U](context.client, context.cfg) {
  private val crdApi = new CrdApi(client, context.crd)

  def currentResources(namespace: K8sNamespace = targetNamespace): Either[Throwable, ResourcesList[T, U]] = {
    val resources = Try(crdApi.listResources(namespace)).toEither
    convert(resources)
  }

  def currentResources(labels: Map[String, String]): Either[Throwable, ResourcesList[T, U]] = {
    val resources = Try(crdApi.listResources[T](labels)).toEither
    convert(resources)
  }

  private def convert(resources: Either[Throwable, CustomResourceList]) =
    resources.map(_.map(convertCr[T, U](context.parser)))
}
