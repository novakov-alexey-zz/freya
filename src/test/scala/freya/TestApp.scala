package freya

import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, IO, IOApp}
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.{DefaultKubernetesClient}

class KrbController[F[_]](implicit override val F: ConcurrentEffect[F]) extends Controller[F, Kerb] with LazyLogging {

  override def onAdd(krb: Kerb, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"new Kerb added: $krb, $meta"))

  override def onDelete(krb: Kerb, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"Kerb deleted: $krb, $meta"))

  override def onModify(krb: Kerb, meta: Metadata): F[Unit] =
    F.delay(logger.info(s"Kerb modified: $krb, $meta"))
}

object TestOperator extends IOApp {
  implicit val cs: ContextShift[IO] = contextShift

  override def run(args: List[String]): IO[ExitCode] = {
    val client = IO(new DefaultKubernetesClient)
    val cfg = CrdConfig(classOf[Kerb], Namespace("test"), "io.github.novakov-alexey")

    Operator
      .ofCrd[IO, Kerb](cfg, client, new KrbController[IO])
      .run
  }
}

final case class Principal(name: String, password: String, value: String = "")
final case class Kerb(realm: String, principals: List[Principal], failInTest: Boolean)
