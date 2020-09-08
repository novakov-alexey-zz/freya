package freya.watcher

import cats.effect.Effect
import cats.effect.concurrent.MVar2
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.ExitCodes.{ConsumerExitCode, FeedbackExitCode}
import freya.JsonWriter
import freya.internal.kubeapi.CrdApi
import freya.internal.kubeapi.CrdApi.StatusUpdate
import freya.watcher.FeedbackConsumer.FeedbackChannel
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient

trait FeedbackConsumerAlg[F[_], T] {
  def consume: F[ConsumerExitCode]
  def put(status: Either[Unit, StatusUpdate[T]]): F[Unit]
}

object FeedbackConsumer {
  type FeedbackChannel[F[_], T] = MVar2[F, Either[Unit, StatusUpdate[T]]]
}

class FeedbackConsumer[F[_], T](
  client: KubernetesClient,
  crd: CustomResourceDefinition,
  channel: FeedbackChannel[F, T]
)(implicit F: Effect[F], writer: JsonWriter[T])
    extends FeedbackConsumerAlg[F, T]
    with LazyLogging {
  private val crdApi = new CrdApi(client, crd)

  def put(status: Either[Unit, StatusUpdate[T]]): F[Unit] =
    channel.put(status)

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
