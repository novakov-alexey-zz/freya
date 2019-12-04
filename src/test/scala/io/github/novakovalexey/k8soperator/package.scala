package io.github.novakovalexey

import cats.effect.{CancelToken, ExitCode, IO}
import io.fabric8.kubernetes.api.model.ConfigMap
import io.github.novakovalexey.k8soperator.internal.resource.ConfigMapParser
import org.scalacheck.{Arbitrary, Gen}

package object k8soperator {
  implicit lazy val arbInfoClass: Arbitrary[Krb2] = Arbitrary(Krb2.gen)
  implicit lazy val arbBooleab: Arbitrary[Boolean] = Arbitrary(Gen.oneOf(true, false))

  def startOperator(io: IO[ExitCode]): CancelToken[IO] =
    io.unsafeRunCancelable {
      case Right(ec) =>
        println(s"Operator stopped with exit code: $ec")
      case Left(t) =>
        println("Failed to start operator")
        t.printStackTrace()
    }

  def parseCM(parser: ConfigMapParser, cm: ConfigMap): Krb2 =
    parser.parseCM(classOf[Krb2], cm).getOrElse(sys.error("Error when transforming ConfigMap to Krb2"))._1
}
