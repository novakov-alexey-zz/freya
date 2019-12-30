package freya

import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, IO, IOApp}
import com.typesafe.scalalogging.LazyLogging
import freya.Configuration.CrdConfig
import freya.K8sNamespace.Namespace
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.DefaultKubernetesClient

import scala.concurrent.duration._

class KrbController[F[_]](implicit F: ConcurrentEffect[F]) extends Controller[F, Kerb] with LazyLogging {

  override def onAdd(krb: Kerb, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"new Kerb added: $krb, $meta"))

  override def onDelete(krb: Kerb, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"Kerb deleted: $krb, $meta"))

  override def onModify(krb: Kerb, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"Kerb modified: $krb, $meta"))
}

class KrbCmController[F[_]](implicit F: ConcurrentEffect[F]) extends Controller[F, Kerb] with CmController {

  override def isSupported(cm: ConfigMap): Boolean =
    cm.getMetadata.getName.startsWith("krb")
}

object TestCmOperator extends IOApp with TestParams {
  implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] = {
    Operator
      .ofConfigMap[IO, Kerb](cmCfg, client, new KrbCmController[IO])
      .run
  }
}

object TestCrdOperator extends IOApp with TestParams {
  implicit val cs: ContextShift[IO] = freya.cs

  override def run(args: List[String]): IO[ExitCode] = {
    Operator
      .ofCrd[IO, Kerb](crdCfg, client, new KrbController[IO])
      .run
  }
}

trait TestParams {
  val client = IO(new DefaultKubernetesClient)
  val crdCfg = CrdConfig(classOf[Kerb], Namespace("test"), prefix, 100.millis)
  val cmCfg = Configuration.ConfigMapConfig(classOf[Kerb], Namespace("test"), prefix)
}

object HelperCrdOperator extends IOApp with LazyLogging with TestParams {
  implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] = {
    val controller = (helper: CrdHelper[IO, Kerb]) =>
      new Controller[IO, Kerb] {

        override def onInit(): IO[Unit] =
          helper.currentResources.fold(
            IO.raiseError,
            r =>
              IO(r.foreach { resource =>
                resource.fold(
                  error => logger.error("Failed to get current CRD instances", error._1),
                  resource => logger.info(s"current ${crdCfg.getKind} CRDs: ${resource._2}")
                )
              })
          )
      }

    Operator
      .ofCrd[IO, Kerb](crdCfg, client)(controller)
      .withRestart()
  }
}
