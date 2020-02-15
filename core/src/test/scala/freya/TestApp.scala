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

class KrbController[F[_]](implicit F: ConcurrentEffect[F]) extends Controller[F, Kerb, Status] with LazyLogging {

  private def getStatus: F[NewStatus[Status]] =
    F.pure(Some(Status(true)))

  override def onAdd(krb: CustomResource[Kerb, Status]): F[NewStatus[Status]] =
    F.delay(logger.info(s"Kerb added event: ${krb.spec}, ${krb.metadata}")) *> getStatus

  override def onDelete(krb: CustomResource[Kerb, Status]): F[Unit] =
    F.delay(logger.info(s"Kerb deleted event: ${krb.spec}, ${krb.metadata}"))

  override def onModify(krb: CustomResource[Kerb, Status]): F[NewStatus[Status]] =
    F.delay(logger.info(s"Kerb modify event: ${krb.spec}, ${krb.metadata}")) *> getStatus
}

class KrbCmController[F[_]](implicit F: ConcurrentEffect[F]) extends CmController[F, Kerb] {

  override def isSupported(cm: ConfigMap): Boolean =
    cm.getMetadata.getName.startsWith("krb")
}

object TestCmOperator extends IOApp with TestParams {
  import freya.yaml.jackson._
  implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] =
    Operator
      .ofConfigMap[IO, Kerb](cmCfg, client, new KrbCmController[IO])
      .run
}

object TestCrdOperator extends IOApp with TestParams {
  import freya.json.jackson._
  implicit val cs: ContextShift[IO] = freya.cs

  override def run(args: List[String]): IO[ExitCode] =
    Operator
      .ofCrd[IO, Kerb, Status](crdCfg, client, new KrbController[IO])
      .withReconciler(60.seconds)
      .run
}

trait TestParams {
  val client = IO(new DefaultKubernetesClient)
  val crdCfg = CrdConfig(Namespace("test"), prefix)
  val cmCfg = Configuration.ConfigMapConfig(Namespace("test"), prefix)
}

object HelperCrdOperator extends IOApp with LazyLogging with TestParams {
  import freya.json.jackson._
  implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] = {
    val controller = (helper: CrdHelper[IO, Kerb, Status]) =>
      new Controller[IO, Kerb, Status] {

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
      .ofCrd[IO, Kerb, Status](crdCfg, client)(controller)
      .withRestart()
  }
}
