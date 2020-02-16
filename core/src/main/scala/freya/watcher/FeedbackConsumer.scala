package freya.watcher

import cats.effect.ConcurrentEffect
import cats.effect.concurrent.MVar
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.ExitCodes.{ConsumerExitCode, FeedbackExitCode}
import freya.JsonWriter
import freya.internal.kubeapi.CrdApi
import freya.internal.kubeapi.CrdApi.StatusUpdate
import freya.watcher.FeedbackConsumer.FeedbackChannel
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient

trait FeedbackConsumerAlg[F[_]] {
  def consume: F[ConsumerExitCode]
}

object FeedbackConsumer {
  type FeedbackChannel[F[_], T] = MVar[F, Either[Unit, StatusUpdate[T]]]
}

class FeedbackConsumer[F[_], T](client: KubernetesClient, crd: CustomResourceDefinition, channel: FeedbackChannel[F, T])(
  implicit F: ConcurrentEffect[F],
  writer: JsonWriter[T]
) extends FeedbackConsumerAlg[F]
    with LazyLogging {
  private val crdApi = new CrdApi(client, crd)

  def consume: F[ConsumerExitCode] =
    for {
      message <- channel.take
      _ <- message match {
        case Left(_) => FeedbackExitCode.pure[F]
        case Right(status) =>
          F.delay(crdApi.updateStatus(status))
            .handleErrorWith(e => F.delay(logger.error(s"Failed to update resource status: $status", e))) *> consume
      }
    } yield FeedbackExitCode
}
