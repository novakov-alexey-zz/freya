package freya

import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, IO, IOApp}
import com.typesafe.scalalogging.LazyLogging
import freya.K8sNamespace.Namespace
import freya.Configuration.CrdConfig
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.DefaultKubernetesClient

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
  implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] = {
    Operator
      .ofCrd[IO, Kerb](crdCfg, client, new KrbController[IO])
      .withRestart()
  }
}

trait TestParams {
  val client = IO(new DefaultKubernetesClient)
  val crdCfg = CrdConfig(classOf[Kerb], Namespace("test"), prefix)
  val cmCfg = Configuration.ConfigMapConfig(classOf[Kerb], Namespace("test"), prefix)
}

object HelperCrdOperator extends IOApp with LazyLogging with TestParams {
  implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] = {
    val controller = (helper: CrdHelper[IO, Kerb]) =>
      new Controller[IO, Kerb] {

        override def onInit(): IO[Unit] =
          IO(
            helper.currentResources.fold(
              errors => logger.error("Failed to get current CRD instances", errors.map(_.getMessage).mkString("\n")),
              crds => logger.info(s"current ${crdCfg.getKind} CRDs: $crds")
            )
          )
      }

    Operator
      .ofCrd[IO, Kerb](crdCfg, client)(controller)
      .withRestart()
  }
}
