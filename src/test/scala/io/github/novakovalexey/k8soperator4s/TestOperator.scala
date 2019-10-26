package io.github.novakovalexey.k8soperator4s

import cats.effect.{ExitCode, IO, IOApp, Sync}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.github.novakovalexey.k8soperator4s.common.{CrdConfig, Metadata, Namespace}

class KrbOperator[F[_]](implicit F: Sync[F]) extends Operator[F, Krb2] with LazyLogging {

  override def onAdd(krb: Krb2, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"new krb added: $krb, $meta"))

  override def onDelete(krb: Krb2, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"krb deleted: $krb, $meta"))
}

object TestOperator extends IOApp with LazyLogging {

  override def run(args: List[String]): IO[ExitCode] = {
    val client = new DefaultKubernetesClient
    val cfg = CrdConfig(classOf[Krb2], Namespace("yp-kss"), "io.github.novakov-alexey")
    val crdOperator = new CrdOperator[IO, Krb2](new KrbOperator[IO], cfg, client)
    val scheduler = new Scheduler[IO, Krb2](crdOperator)
    scheduler
      .start()
      .bracket { _ =>
        IO.pure(ExitCode.Success)
      } { w =>
        IO(w.close()).handleErrorWith(_ => IO.unit) >> IO.suspend(scheduler.stop()) >> IO(println("Server finished"))
      }
  }
}

final case class Principal(name: String, password: String, value: String = "")
final case class Keytab(secret: String, key: String, principal: String, realm: String)
final case class Krb2(principals: List[Principal], keytabs: List[Keytab])
