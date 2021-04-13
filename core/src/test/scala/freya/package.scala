import cats.effect.{ExitCode, IO}
import freya.internal.kubeapi.MetadataApi
import freya.models.Metadata
import freya.resource.ConfigMapParser
import freya.yaml.jackson._
import io.fabric8.kubernetes.api.model.ConfigMap
import org.scalacheck.{Arbitrary, Gen}

import scala.concurrent.Future
import cats.effect.unsafe.implicits._
import com.typesafe.scalalogging.LazyLogging

package object freya extends LazyLogging {
  implicit lazy val arbInfoClass: Arbitrary[Kerb] = Arbitrary(Gens.krb2)
  implicit lazy val arbBoolean: Arbitrary[Boolean] = Arbitrary(Gen.oneOf(true, false))

  val prefix = "io.github.novakov-alexey"

  def startOperator(io: IO[ExitCode]): Future[ExitCode] =
    io.unsafeToFuture()

  def parseCM(parser: ConfigMapParser, cm: ConfigMap): Kerb = {
    val (kerb, _) = parser.parseCM[Kerb](cm).getOrElse(sys.error("Error when transforming ConfigMap to Kerb instance"))
    kerb
  }

  def toMetadata(cm: ConfigMap): Metadata =
    MetadataApi.translate(cm.getMetadata)
}
