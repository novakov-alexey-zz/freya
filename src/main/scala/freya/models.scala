package freya

import cats.effect.ExitCode
import freya.watcher.SpecClass
import io.fabric8.kubernetes.api.model.HasMetadata

object models {
  type Resource[T] = Either[(Throwable, HasMetadata), (T, Metadata)]
  type ResourcesList[T] = List[Resource[T]]
}

object signals {
  type ConsumerSignal = ExitCode
  type ReconcilerSignal = ExitCode
  type OperatorSignal = Either[ConsumerSignal, ReconcilerSignal]

  val WatcherClosedSignal: ConsumerSignal = ExitCode(2)
  val ReconcileExitCode: ReconcilerSignal = ExitCode(3)
}


trait CustomResourceParser {
  def parse[T](clazz: Class[T], specClass: SpecClass): Either[Throwable, T]
}