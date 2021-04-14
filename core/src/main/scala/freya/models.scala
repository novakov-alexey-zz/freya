package freya

import cats.effect.ExitCode
import freya.internal.kubeapi.MetadataApi
import io.fabric8.kubernetes.api.model.ObjectMeta

object models {
  type Resource[T, U] = Either[(Throwable, String), CustomResource[T, U]]
  type ResourcesList[T, U] = List[Resource[T, U]]
  type NewStatus[U] = Option[U]
  type NoStatus = NewStatus[Unit]

  final case class Metadata(
    name: String,
    namespace: String,
    labels: Map[String, String],
    resourceVersion: String,
    uid: String
  )

  object Metadata {
    def fromObjectMeta(m: ObjectMeta): Metadata =
      MetadataApi.translate(m)
  }

  final case class CustomResource[T, U](metadata: Metadata, spec: T, status: NewStatus[U])
}

object ExitCodes {
  type ConsumerExitCode = ExitCode
  type ReconcilerExitCode = ExitCode
  type OperatorExitCode = Either[ConsumerExitCode, ReconcilerExitCode]

  val ActionConsumerExitCode: ConsumerExitCode = ExitCode(2)
  val ReconcileExitCode: ReconcilerExitCode = ExitCode(3)
  val FeedbackExitCode: ConsumerExitCode = ExitCode(4)
}

trait CustomResourceParser {
  def parseStr[T: JsonReader, U: JsonReader](cr: String): Either[Throwable, (T, Option[U], ObjectMeta)]
  def parse[T: JsonReader, U: JsonReader](cr: AnyRef): Either[(Throwable, String), (T, Option[U], ObjectMeta)]
}
