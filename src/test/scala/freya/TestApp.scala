package freya

import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, IO, IOApp}
import com.typesafe.scalalogging.LazyLogging
import freya.K8sNamespace.Namespace
import freya.OperatorCfg.Crd
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.client.DefaultKubernetesClient

class KrbController[F[_]](implicit F: ConcurrentEffect[F]) extends Controller[F, Kerb] with LazyLogging {

  override def onAdd(krb: Kerb, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"new Kerb added: $krb, $meta"))

  override def onDelete(krb: Kerb, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"Kerb deleted: $krb, $meta"))

  override def onModify(krb: Kerb, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"Kerb modified: $krb, $meta"))

  override def onInit(): F[Unit] =
    F.delay(logger.info(s"init completed"))
}

class KrbCmController[F[_]](implicit F: ConcurrentEffect[F]) extends Controller[F, Kerb] with CmController {

  override def isSupported(cm: ConfigMap): Boolean =
    cm.getMetadata.getName.startsWith("krb")
}

object TestCmOperator extends IOApp {
  implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] = {
    val client = IO(new DefaultKubernetesClient)
    val cfg = OperatorCfg.ConfigMap(classOf[Kerb], Namespace("test"), prefix)

    Operator
      .ofConfigMap[IO, Kerb](cfg, client, new KrbCmController[IO])
      .run
  }
}

object TestCrdOperator extends IOApp {
  implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] = {
    val client = IO(new DefaultKubernetesClient)
    val cfg = Crd(classOf[Kerb], Namespace("test"), prefix)

    Operator
      .ofCrd[IO, Kerb](cfg, client, new KrbController[IO])
      .withRestart()
  }
}
