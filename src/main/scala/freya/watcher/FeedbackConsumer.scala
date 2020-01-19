package freya.watcher

import cats.effect.concurrent.MVar
import cats.effect.{ConcurrentEffect, ExitCode}
import cats.implicits._
import freya.ExitCodes
import freya.K8sNamespace.AllNamespaces
import freya.internal.api.CrdApi
import freya.models.CustomResource
import freya.watcher.FeedbackConsumer.FeedbackChannel
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient

trait FeedbackConsumerAlg[F[_]] {
  def consume: F[ExitCode]
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

  def consume: F[ExitCode] =
    for {
      message <- channel.take
      _ <- message match {
        case Left(()) => ExitCodes.FeedbackExitCode.pure[F]
        case Right(cr) =>
          val anyCr = {
            val _anyCr = new AnyCustomResource
            _anyCr.setSpec(cr.spec.asInstanceOf[AnyRef])
            _anyCr.setStatus(cr.status.asInstanceOf[AnyRef])
            _anyCr
          }
          F.delay(crdApi.in(AllNamespaces, crd).updateStatus(anyCr)) *> consume
      }
    } yield ExitCodes.FeedbackExitCode
}
