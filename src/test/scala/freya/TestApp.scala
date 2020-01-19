package freya

import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, IO, IOApp}
import cats.syntax.apply._
import com.typesafe.scalalogging.LazyLogging
import freya.Configuration.CrdConfig
import freya.K8sNamespace.Namespace
import freya.models.{CustomResource, NewStatus}
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.DefaultKubernetesClient

import scala.concurrent.duration._

class KrbController[F[_]](implicit F: ConcurrentEffect[F]) extends Controller[F, Kerb, KerbStatus] with LazyLogging {

  private def noStatus: F[NewStatus[KerbStatus]] =
    F.pure(Some(KerbStatus()))

  override def onAdd(krb: CustomResource[Kerb, KerbStatus]): F[NewStatus[KerbStatus]] =
    F.delay(logger.info(s"new Kerb added: ${krb.spec}, ${krb.metadata}")) *> noStatus

  override def onDelete(krb: CustomResource[Kerb, KerbStatus]): F[NewStatus[KerbStatus]] =
    F.delay(logger.info(s"new Kerb deleted: ${krb.spec}, ${krb.metadata}")) *> noStatus

  override def onModify(krb: CustomResource[Kerb, KerbStatus]): F[NewStatus[KerbStatus]] =
    F.delay(logger.info(s"new Kerb deleted: ${krb.spec}, ${krb.metadata}")) *> noStatus
}

class KrbCmController[F[_]](implicit F: ConcurrentEffect[F]) extends CmController[F, Kerb] {

  override def isSupported(cm: ConfigMap): Boolean =
    cm.getMetadata.getName.startsWith("krb")
}

object TestCmOperator extends IOApp with TestParams {
  implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] =
    Operator
      .ofConfigMap[IO, Kerb](cmCfg, client, new KrbCmController[IO])
      .run
}

object TestCrdOperator extends IOApp with TestParams {
  implicit val cs: ContextShift[IO] = freya.cs

  override def run(args: List[String]): IO[ExitCode] =
    Operator
      .ofCrd[IO, Kerb, KerbStatus](crdCfg, client, new KrbController[IO])
      .withReconciler(60.seconds)
      .run
}

trait TestParams {
  val client = IO(new DefaultKubernetesClient)
  val crdCfg = CrdConfig[Kerb](Namespace("test"), prefix)
  val cmCfg = Configuration.ConfigMapConfig[Kerb](Namespace("test"), prefix)
}

object HelperCrdOperator extends IOApp with LazyLogging with TestParams {
  implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] = {
    val controller = (helper: CrdHelper[IO, Kerb, KerbStatus]) =>
      new Controller[IO, Kerb, KerbStatus] {

        override def onInit(): IO[Unit] =
          helper.currentResources.fold(
            IO.raiseError,
            r =>
              IO(r.foreach {
                case Left((error, r)) => logger.error(s"Failed to parse CRD instances $r", error)
                case Right(resource) => logger.info(s"current ${crdCfg.getKind} CRDs: ${resource.spec}")
              })
          )
      }

    Operator
      .ofCrd[IO, Kerb, KerbStatus](crdCfg, client)(controller)
      .withRestart()
  }
}
