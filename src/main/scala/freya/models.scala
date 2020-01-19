package freya

import cats.effect.ExitCode
import freya.watcher.AnyCustomResource
import io.fabric8.kubernetes.api.model.HasMetadata

object models {
  type Resource[T, U] = Either[(Throwable, HasMetadata), CustomResource[T, U]]
  type ResourcesList[T, U] = List[Resource[T, U]]
  type NewStatus[U] = Option[U]
  final case class CustomResource[T, U](spec: T, metadata: Metadata, status: U)
}

object ExitCodes {
  type ConsumerExitCode = ExitCode
  type ReconcilerExitCode = ExitCode
  type OperatorExitCode = Either[ConsumerExitCode, ReconcilerExitCode]

  val WatcherClosedExitCode: ConsumerExitCode = ExitCode(2)
  val ReconcileExitCode: ReconcilerExitCode = ExitCode(3)
  val FeedbackExitCode: ConsumerExitCode = ExitCode(4)
}

trait CustomResourceParser {
  def parse[T, U](spec: Class[T], status: Class[U], cr: AnyCustomResource): Either[Throwable, (T, U)]
}
