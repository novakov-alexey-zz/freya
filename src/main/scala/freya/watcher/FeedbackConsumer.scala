package freya.watcher

import cats.effect.ConcurrentEffect
import cats.effect.concurrent.MVar
import cats.implicits._
import freya.ExitCodes.{ConsumerExitCode, FeedbackExitCode}
import freya.internal.api.CrdApi
import freya.models.CustomResource
import freya.watcher.FeedbackConsumer.FeedbackChannel
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient

trait FeedbackConsumerAlg[F[_]] {
  def consume: F[ConsumerExitCode]
}

object FeedbackConsumer {
  type FeedbackChannel[F[_], T, U] = MVar[F, Either[Unit, CustomResource[T, U]]]
}

class FeedbackConsumer[F[_], T, U](
  client: KubernetesClient,
  crd: CustomResourceDefinition,
  channel: FeedbackChannel[F, T, U]
)(implicit F: ConcurrentEffect[F])
    extends FeedbackConsumerAlg[F] {
  private val crdApi = new CrdApi(client)

  def consume: F[ConsumerExitCode] =
    for {
      message <- channel.take
      _ <- message match {
        case Left(_) => FeedbackExitCode.pure[F]
        case Right(cr) => F.delay(crdApi.updateStatus(crd, cr)) *> consume
      }
    } yield FeedbackExitCode
}
