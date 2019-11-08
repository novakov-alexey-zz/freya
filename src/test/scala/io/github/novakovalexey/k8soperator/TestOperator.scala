package io.github.novakovalexey.k8soperator

import cats.effect.{ConcurrentEffect, ExitCode, IO, IOApp}
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.DefaultKubernetesClient

class KrbController[F[_]](implicit F: ConcurrentEffect[F]) extends Controller[F, Krb2] with LazyLogging {

  override def onAdd(krb: Krb2, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"new Krb added: $krb, $meta"))

  override def onDelete(krb: Krb2, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"Krb deleted: $krb, $meta"))
}

object TestOperator extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    val client = new DefaultKubernetesClient
    val cfg = CrdConfig(classOf[Krb2], Namespace("yp-kss"), "io.github.novakov-alexey")

    Operator
      .ofCrd[IO, Krb2](cfg, client, new KrbController[IO])
      .run
  }
}

final case class Principal(name: String, password: String, value: String = "")
final case class Keytab(secret: String, key: String, principal: String, realm: String)
final case class Krb2(principals: List[Principal], keytabs: List[Keytab])
