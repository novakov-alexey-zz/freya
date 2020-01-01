import cats.effect.{CancelToken, ContextShift, ExitCode, IO, Timer}
import freya.resource.ConfigMapParser
import io.fabric8.kubernetes.api.model.ConfigMap
import org.scalacheck.{Arbitrary, Gen}

import scala.concurrent.ExecutionContext

package object freya {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
  implicit lazy val arbInfoClass: Arbitrary[Kerb] = Arbitrary(Gens.krb2)
  implicit lazy val arbBoolean: Arbitrary[Boolean] = Arbitrary(Gen.oneOf(true, false))

  val prefix = "io.github.novakov-alexey"

  def startOperator(io: IO[ExitCode]): CancelToken[IO] =
    io.unsafeRunCancelable {
      case Right(ec) =>
        println(s"Operator stopped with exit code: $ec")
      case Left(t) =>
        println("Failed to start operator")
        t.printStackTrace()
    }

  def parseCM(parser: ConfigMapParser, cm: ConfigMap): Kerb = {
    val (kerb, _) = parser.parseCM(classOf[Kerb], cm).getOrElse(sys.error("Error when transforming ConfigMap to Krb2"))
    kerb
  }

  def toMetadata(cm: ConfigMap): Metadata =
    Metadata(cm.getMetadata.getName, cm.getMetadata.getNamespace)
}
