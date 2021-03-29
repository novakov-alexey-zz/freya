import cats.effect.{CancelToken, ExitCode, IO}
import freya.internal.kubeapi.MetadataApi
import freya.models.Metadata
import freya.resource.ConfigMapParser
import freya.yaml.jackson._
import io.fabric8.kubernetes.api.model.ConfigMap
import org.scalacheck.{Arbitrary, Gen}

import scala.concurrent.ExecutionContext
import cats.effect.Temporal

package object freya {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Temporal[IO] = IO.timer(ExecutionContext.global)
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
    val (kerb, _) = parser.parseCM[Kerb](cm).getOrElse(sys.error("Error when transforming ConfigMap to Kerb"))
    kerb
  }

  def toMetadata(cm: ConfigMap): Metadata =
    MetadataApi.translate(cm.getMetadata)
}
