package freya

import cats.Parallel
import cats.effect.ExitCase.Canceled
import cats.effect.concurrent.MVar
import cats.effect.syntax.all._
import cats.effect.{Concurrent, ConcurrentEffect, ExitCode, Resource, Sync, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.Configuration.{ConfigMapConfig, CrdConfig}
import freya.ExitCodes.{ConsumerExitCode, OperatorExitCode, ReconcilerExitCode}
import freya.Retry.{Infinite, Times}
import freya.errors.OperatorError
import freya.internal.AnsiColors._
import freya.internal.OperatorUtils._
import freya.internal.Reconciler
import freya.internal.crd.Deployer
import freya.internal.kubeapi.CrdApi.StatusUpdate
import freya.resource.{ConfigMapParser, CrdParser, Labels}
import freya.watcher.AbstractWatcher.{Channel, CloseableWatcher}
import freya.watcher.FeedbackConsumer.FeedbackChannel
import freya.watcher._
import freya.watcher.actions.OperatorAction
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client._

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.Random

trait CrdWatchMaker[F[_], T, U] {
  def make(context: CrdWatcherContext[F, T, U]): WatcherMaker[F]
}

object CrdWatchMaker {
  implicit def crd[F[_]: ConcurrentEffect: Parallel: Timer, T, U]: CrdWatchMaker[F, T, U] =
    (context: CrdWatcherContext[F, T, U]) => new CustomResourceWatcher(context)
}

trait ConfigMapWatchMaker[F[_], T] {
  def make(context: ConfigMapWatcherContext[F, T]): WatcherMaker[F]
}

object ConfigMapWatchMaker {
  implicit def cm[F[_]: ConcurrentEffect: Parallel: Timer, T]: ConfigMapWatchMaker[F, T] =
    (context: ConfigMapWatcherContext[F, T]) => new ConfigMapWatcher(context)
}

trait CrdDeployer[F[_]] {
  def deployCrd[T: JsonReader](
    client: KubernetesClient,
    cfg: CrdConfig,
    isOpenShift: Option[Boolean]
  ): F[CustomResourceDefinition]
}

object CrdDeployer {
  implicit def deployer[F[_]: Sync]: CrdDeployer[F] = new CrdDeployer[F] {
    override def deployCrd[T: JsonReader](
      client: KubernetesClient,
      cfg: CrdConfig,
      isOpenShift: Option[Boolean]
    ): F[CustomResourceDefinition] =
      Deployer.deployCrd[F, T](client, cfg, isOpenShift)
  }
}

trait FeedbackConsumerMaker[F[_], T] {
  def make(
    client: KubernetesClient,
    crd: CustomResourceDefinition,
    channel: FeedbackChannel[F, T]
  ): FeedbackConsumerAlg[F]
}

object FeedbackConsumerMaker {
  implicit def consumer[F[_]: ConcurrentEffect, T: JsonWriter]: FeedbackConsumerMaker[F, T] =
    (client: KubernetesClient, crd: CustomResourceDefinition, channel: FeedbackChannel[F, T]) =>
      new FeedbackConsumer[F, T](client, crd, channel)
}

object Operator extends LazyLogging {

  def ofCrd[F[_]: ConcurrentEffect: Timer: CrdDeployer: Parallel, T: JsonReader](
    cfg: CrdConfig,
    client: F[KubernetesClient],
    controller: Controller[F, T, Unit]
  )(
    implicit watch: CrdWatchMaker[F, T, Unit],
    helper: CrdHelperMaker[F, T, Unit],
    consumer: FeedbackConsumerMaker[F, Unit]
  ): Operator[F, T, Unit] =
    ofCrd[F, T, Unit](cfg, client)((_: CrdHelper[F, T, Unit]) => controller)

  def ofCrd[F[_]: ConcurrentEffect: Timer: CrdDeployer: Parallel, T: JsonReader, U: JsonReader: JsonWriter](
    cfg: CrdConfig,
    client: F[KubernetesClient],
    controller: Controller[F, T, U]
  )(
    implicit watch: CrdWatchMaker[F, T, U],
    helper: CrdHelperMaker[F, T, U],
    consumer: FeedbackConsumerMaker[F, U]
  ): Operator[F, T, U] =
    ofCrd[F, T, U](cfg, client)((_: CrdHelper[F, T, U]) => controller)

  def ofCrd[F[_]: Timer: Parallel, T: JsonReader, U: JsonReader: JsonWriter](
    cfg: CrdConfig,
    client: F[KubernetesClient]
  )(controller: CrdHelper[F, T, U] => Controller[F, T, U])(
    implicit F: ConcurrentEffect[F],
    watch: CrdWatchMaker[F, T, U],
    crdHelper: CrdHelperMaker[F, T, U],
    deployer: CrdDeployer[F],
    feedbackConsumer: FeedbackConsumerMaker[F, U]
  ): Operator[F, T, U] = {

    val pipeline = for {
      c <- client
      isOpenShift <- checkEnvAndConfig[F, T](c, cfg)
      crd <- deployer.deployCrd[T](c, cfg, isOpenShift)
      channelPair <- createChannels[F, T, U](cfg.parallelism)
      parser <- CrdParser()

      helper = {
        val context = CrdHelperContext(cfg, c, isOpenShift, crd, parser)
        crdHelper.make(context)
      }
      ctl = controller(helper)
      channels = createChannels[F, T, U](channelPair, c, crd, ctl, cfg.getKind[T])
      context = CrdWatcherContext(cfg.namespace, cfg.getKind[T], channels, CrdHelper.convertCr[T, U](parser), c, crd)

      w <- F.delay(watch.make(context).watch)
    } yield createPipeline(helper, ctl, w, channels)

    new Operator[F, T, U](pipeline)
  }

  private def createChannels[F[_]: Timer: Parallel: ConcurrentEffect, T: JsonReader, U: JsonReader: JsonWriter](
    channelPair: List[(Channel[F, T, U], MVar[F, Either[Unit, StatusUpdate[U]]])],
    client: KubernetesClient,
    crd: CustomResourceDefinition,
    ctl: Controller[F, T, U],
    kind: String
  )(implicit feedbackConsumer: FeedbackConsumerMaker[F, U]) = {
    val (actionConsumers, feedbackConsumers) =
      channelPair.zipWithIndex.foldLeft((List.empty[ActionConsumer[F, T, U]], List.empty[FeedbackConsumerAlg[F]])) {
        case ((ac, fc), ((action, feedback), i)) =>
          (
            ac :+ new ActionConsumer[F, T, U](i, ctl, kind, action, feedback),
            fc :+ feedbackConsumer.make(client, crd, feedback)
          )
      }
    new Channels(actionConsumers, feedbackConsumers)
  }

  private def newFeedbackChannel[F[_]: ConcurrentEffect, U] =
    MVar[F].empty[Either[Unit, StatusUpdate[U]]]

  def ofConfigMap[F[_]: ConcurrentEffect: Timer: Parallel, T: YamlReader](
    cfg: ConfigMapConfig,
    client: F[KubernetesClient],
    controller: CmController[F, T]
  )(implicit watchMaker: ConfigMapWatchMaker[F, T], helper: ConfigMapHelperMaker[F, T]): Operator[F, T, Unit] =
    ofConfigMap[F, T](cfg, client)((_: ConfigMapHelper[F, T]) => controller)

  def ofConfigMap[F[_]: Timer: Parallel, T: YamlReader](cfg: ConfigMapConfig, client: F[KubernetesClient])(
    controller: ConfigMapHelper[F, T] => CmController[F, T]
  )(
    implicit F: ConcurrentEffect[F],
    watchMaker: ConfigMapWatchMaker[F, T],
    helperMaker: ConfigMapHelperMaker[F, T]
  ): Operator[F, T, Unit] = {

    val pipeline = for {
      k8sClient <- client
      isOpenShift <- checkEnvAndConfig(k8sClient, cfg)
      channelPair <- createChannels[F, T, Unit](cfg.parallelism)
      parser <- ConfigMapParser()
      helper = {
        val context = ConfigMapHelperContext(cfg, k8sClient, isOpenShift, parser)
        helperMaker.make(context)
      }
      ctl = controller(helper)
      channels = new Channels[F, T, Unit](channelPair.zipWithIndex.map {
        case ((action, feedback), i) => new ActionConsumer[F, T, Unit](i, ctl, cfg.getKind[T], action, feedback)
      }, Nil)
      context = ConfigMapWatcherContext(
        cfg.namespace,
        cfg.getKind,
        ctl,
        channels,
        ConfigMapHelper.convertCm[T](parser),
        k8sClient,
        Labels.forKind(cfg.getKind, cfg.prefix)
      )
      w <- F.delay(watchMaker.make(context).watch)
    } yield createPipeline(helper, ctl, w, channels)

    new Operator[F, T, Unit](pipeline)
  }

  private def createChannels[F[_]: Timer: Parallel: ConcurrentEffect, T, U](parallelism: Int) =
    (0 until parallelism).map { _ =>
      for {
        actionChannel <- newActionChannel[F, T, U]
        feedbackChannel <- newFeedbackChannel[F, U]
      } yield (actionChannel, feedbackChannel)
    }.toList.sequence

  private def newActionChannel[F[_]: Concurrent, T, U]: F[Channel[F, T, U]] =
    MVar[F].empty[Either[OperatorError, OperatorAction[T, U]]]

  private def createPipeline[F[_]: ConcurrentEffect, T, U](
    helper: AbstractHelper[F, T, U],
    controller: Controller[F, T, U],
    watcher: F[(CloseableWatcher, F[ConsumerExitCode])],
    channels: Channels[F, T, U]
  ) =
    OperatorPipeline[F, T, U](helper, watcher, channels, controller.onInit())

  private def checkEnvAndConfig[F[_]: Sync, T: JsonReader](
    client: KubernetesClient,
    cfg: Configuration
  ): F[Option[Boolean]] =
    for {
      _ <- Sync[F].fromEither(cfg.validate.leftMap(new RuntimeException(_)))
      check <- if (cfg.checkK8sOnStartup) checkKubeEnv(client) else Option.empty[Boolean].pure[F]
    } yield check

  private def checkKubeEnv[T, F[_]: Sync](client: KubernetesClient) =
    Sync[F].delay {
      val (onOpenShift, code) = checkIfOnOpenshift(client.getMasterUrl)
      if (onOpenShift) logger.debug(s"Returned code: $code. We are on OpenShift.")
      else logger.debug(s"Returned code: $code. We are not on OpenShift. Assuming, we are on Kubernetes.")
      onOpenShift.some
    }
}

private case class OperatorPipeline[F[_], T, U](
  helper: AbstractHelper[F, T, U],
  consumer: F[(CloseableWatcher, F[ConsumerExitCode])],
  channels: Channels[F, T, U],
  onInit: F[Unit]
)

class Operator[F[_], T: Reader, U] private (
  pipeline: F[OperatorPipeline[F, T, U]],
  reconcilerInterval: Option[FiniteDuration] = None
)(implicit F: ConcurrentEffect[F], T: Timer[F])
    extends LazyLogging {

  def run: F[ExitCode] =
    Resource
      .make(start) {
        case (_, consumer) =>
          F.delay(consumer.close()) *> F.delay(logger.info(s"${re}Operator stopped$xx"))
      }
      .use {
        case (signal, _) => signal.map(_.fold(identity, identity))
      }
      .recoverWith {
        case e =>
          F.delay(logger.error("Got error while running an operator", e)) *> ExitCode.Error.pure[F]
      }

  def withReconciler(interval: FiniteDuration): Operator[F, T, U] =
    new Operator[F, T, U](pipeline, Some(interval))

  def withRestart(retry: Retry = Infinite())(implicit T: Timer[F]): F[ExitCode] =
    run.flatMap(loop(_, retry))

  private def loop(ec: ExitCode, retry: Retry)(implicit T: Timer[F]): F[ExitCode] = {
    val (canRestart, delay, nextRetry, remaining) = retry match {
      case Times(maxRetries, delay, multiplier) =>
        (
          maxRetries > 0,
          delay,
          F.delay[Retry](Times(maxRetries - 1, Retry.nextDelay(delay, multiplier), multiplier)),
          maxRetries.toString
        )
      case i @ Infinite(minDelay, maxDelay) =>
        val minSeconds = minDelay.toSeconds
        (true, (Random.nextLong(maxDelay.toSeconds - minSeconds) + minSeconds).seconds, F.pure[Retry](i), "infinite")
    }
    if (canRestart)
      for {
        _ <- F.delay(logger.info(s"Sleeping for $delay"))
        _ <- T.sleep(delay)
        _ <- F.delay(logger.info(s"${re}Going to restart$xx. Restarts left: $remaining"))
        r <- nextRetry
        code <- withRestart(r)
      } yield code
    else ec.pure[F]
  }

  def start: F[(F[OperatorExitCode], CloseableWatcher)] =
    (for {
      pipe <- pipeline

      kind = pipe.helper.cfg.getKind
      namespace = pipe.helper.targetNamespace

      _ <- F.delay(logger.info(s"Starting operator $ye$kind$xx in namespace '$namespace''"))
      _ <- pipe.onInit
      (closableWatcher, consumer) <- pipe.consumer
      _ <- F
        .delay(
          logger
            .info(s"${gr}Operator $kind was started$xx in namespace '$namespace'")
        )
      reconciler = runReconciler(pipe, kind, namespace)
    } yield (F.race(consumer, reconciler), closableWatcher)).onError {
      case ex: Throwable =>
        F.delay(logger.error(s"Could not to start operator", ex))
    }

  private def runReconciler(pipe: OperatorPipeline[F, T, U], kind: String, namespace: K8sNamespace) =
    reconcilerInterval match {
      case None => F.never[ReconcilerExitCode]
      case Some(i) =>
        val r = new Reconciler[F, T, U](i, pipe.channels, F.delay(pipe.helper.currentResources))
        F.delay(logger.info(s"${gr}Starting reconciler $kind$xx in namespace '$namespace' with $i interval")) *>
          r.run.guaranteeCase {
            case Canceled => F.delay(logger.debug("Reconciler was canceled!"))
            case _ => F.unit
          }
    }
}
