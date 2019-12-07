package io.github.novakovalexey.k8soperator

import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, IO, IOApp}
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.DefaultKubernetesClient

import scala.beans.BeanProperty
import scala.concurrent.ExecutionContext

class KrbController[F[_]](implicit override val F: ConcurrentEffect[F]) extends Controller[F, Kerb] with LazyLogging {

  override def onAdd(krb: Kerb, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"new Krb added: $krb, $meta"))

  override def onDelete(krb: Kerb, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"Krb deleted: $krb, $meta"))
}

object TestOperator extends IOApp {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  override def run(args: List[String]): IO[ExitCode] = {
    val client = IO(new DefaultKubernetesClient)
    val cfg = CrdConfig(classOf[Kerb], Namespace("test"), "io.github.novakov-alexey")

    Operator
      .ofCrd[IO, Kerb](cfg, client, new KrbController[IO])
      .withRestart()
  }
}

final case class Principal(
  @BeanProperty val name: String,
  @BeanProperty val password: String,
  @BeanProperty val value: String = ""
)
final case class Kerb(
  @BeanProperty val realm: String,
  @BeanProperty val principals: List[Principal],
  @BeanProperty val failInTest: Boolean
)
