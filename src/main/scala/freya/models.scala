package freya

import cats.effect.ExitCode
import freya.watcher.SpecClass
import io.fabric8.kubernetes.api.model.HasMetadata

object models {
  type Resource[T] = Either[(Throwable, HasMetadata), (T, Metadata)]
  type ResourcesList[T] = List[Resource[T]]
}

object ExitCodes {
  type ConsumerExitCode = ExitCode
  type ReconcilerExitCode = ExitCode
  type OperatorExitCode = Either[ConsumerExitCode, ReconcilerExitCode]

  val WatcherClosedExitCode: ConsumerExitCode = ExitCode(2)
  val ReconcileExitCode: ReconcilerExitCode = ExitCode(3)
}

trait CustomResourceParser {
  def parse[T](clazz: Class[T], specClass: SpecClass): Either[Throwable, T]
}
