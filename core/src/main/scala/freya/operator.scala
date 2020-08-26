package freya

import cats.Parallel
import cats.effect.ExitCase.Canceled
import cats.effect.concurrent.MVar
import cats.effect.syntax.all._
import cats.effect.{ConcurrentEffect, ExitCode, Resource, Sync, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.Configuration.{ConfigMapConfig, CrdConfig}
import freya.ExitCodes.{ConsumerExitCode, ReconcilerExitCode}
import freya.Retry.{Infinite, Times}
import freya.internal.AnsiColors._
import freya.internal.kubeapi.CrdApi.StatusUpdate
import freya.internal.{OperatorUtils, Reconciler}
import freya.resource.{ConfigMapParser, CrdParser, Labels}
import freya.watcher.AbstractWatcher.{Action, CloseableWatcher}
import freya.watcher.FeedbackConsumer.FeedbackChannel
import freya.watcher._
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition
import io.fabric8.kubernetes.client._

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.Random

object Operator extends LazyLogging {

  def ofCrd[F[_]: ConcurrentEffect: Timer: CrdDeployer: Parallel, T: JsonReader](
    cfg: CrdConfig,
    client: F[KubernetesClient],
    controller: KubernetesClient => Controller[F, T, Unit]
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
    ofCrd[F, T, U](cfg, client)((_: CrdHelper[F, T, U]) => (_: KubernetesClient) => controller)

  def ofCrd[F[_]: ConcurrentEffect: Timer: CrdDeployer: Parallel, T: JsonReader, U: JsonReader: JsonWriter](
    cfg: CrdConfig,
    client: F[KubernetesClient],
    controller: CrdHelper[F, T, U] => Controller[F, T, U]
  )(
    implicit watch: CrdWatchMaker[F, T, U],
    helper: CrdHelperMaker[F, T, U],
    consumer: FeedbackConsumerMaker[F, U]
  ): Operator[F, T, U] =
    ofCrd[F, T, U](cfg, client)((h: CrdHelper[F, T, U]) => (_: KubernetesClient) => controller(h))

  def ofCrd[F[_]: Timer: Parallel, T: JsonReader, U: JsonReader: JsonWriter](
    cfg: CrdConfig,
    client: F[KubernetesClient]
  )(controller: CrdHelper[F, T, U] => KubernetesClient => Controller[F, T, U])(
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
      parser <- CrdParser()
      stopFlag <- MVar[F].empty[ConsumerExitCode]
      feedbackChannel <- MVar[F].empty[Either[Unit, StatusUpdate[U]]]
      helper = {
        val context = CrdHelperContext(cfg, c, isOpenShift, crd, parser)
        crdHelper.make(context)
      }
      ctl = controller(helper)(c)
      channels = createChannels[F, T, U](feedbackChannel, c, crd, ctl, cfg)
      context = CrdWatcherContext(
        cfg.namespace,
        cfg.getKind[T],
        channels,
        CrdHelper.convertCr[T, U](parser),
        c,
        crd,
        stopFlag
      )
      w <- F.delay(watch.make(context).watch)
    } yield createPipeline(helper, ctl, w, channels)

    new Operator[F, T, U](pipeline)
  }

  private def createChannels[F[_]: Timer: Parallel: ConcurrentEffect, T: JsonReader, U: JsonReader: JsonWriter](
    feedbackChannel: FeedbackChannel[F, U],
    client: KubernetesClient,
    crd: CustomResourceDefinition,
    ctl: Controller[F, T, U],
    cfg: CrdConfig
  )(implicit feedbackConsumer: FeedbackConsumerMaker[F, U]) = {
    val makeConsumer =
      (namespace: String, feedback: Option[FeedbackConsumerAlg[F, U]]) => {
        val queue = BlockingQueue.create[F, Action[T, U]](cfg.eventQueueSize, namespace)
        queue.map(q => new ActionConsumer[F, T, U](namespace, ctl, cfg.getKind[T], q, feedback))
      }
    val makeFeedbackConsumer = () => feedbackConsumer.make(client, crd, feedbackChannel).some
    new Channels(cfg.concurrentController, makeConsumer, makeFeedbackConsumer)
  }

  def ofConfigMap[F[_]: ConcurrentEffect: Timer: Parallel, T: YamlReader](
    cfg: ConfigMapConfig,
    client: F[KubernetesClient],
    controller: CmController[F, T]
  )(implicit watchMaker: ConfigMapWatchMaker[F, T], helper: ConfigMapHelperMaker[F, T]): Operator[F, T, Unit] =
    ofConfigMap[F, T](cfg, client)((_: ConfigMapHelper[F, T]) => controller)

  def ofConfigMap[F[_]: Timer: Parallel, T: YamlReader](cfg: ConfigMapConfig, client: F[KubernetesClient])(
    makeController: ConfigMapHelper[F, T] => CmController[F, T]
  )(
    implicit F: ConcurrentEffect[F],
    watchMaker: ConfigMapWatchMaker[F, T],
    helperMaker: ConfigMapHelperMaker[F, T]
  ): Operator[F, T, Unit] = {

    val pipeline = for {
      k8sClient <- client
      isOpenShift <- checkEnvAndConfig(k8sClient, cfg)
      stopChannel <- MVar[F].empty[ConsumerExitCode]
      parser <- ConfigMapParser()
      helper = {
        val context = ConfigMapHelperContext(cfg, k8sClient, isOpenShift, parser)
        helperMaker.make(context)
      }
      controller = makeController(helper)
      channels = {
        val makeConsumer =
          (namespace: String, feedback: Option[FeedbackConsumerAlg[F, Unit]]) => {
            val queue = BlockingQueue.create[F, Action[T, Unit]](cfg.eventQueueSize, namespace)
            queue.map(q => new ActionConsumer[F, T, Unit](namespace, controller, cfg.getKind[T], q, feedback))
          }
        new Channels[F, T, Unit](cfg.concurrentController, makeConsumer, () => None)
      }
      context = ConfigMapWatcherContext(
        cfg.namespace,
        cfg.getKind,
        controller,
        channels,
        ConfigMapHelper.convertCm[T](parser),
        k8sClient,
        Labels.forKind(cfg.getKind, cfg.prefix),
        stopChannel
      )
      w <- F.delay(watchMaker.make(context).watch)
    } yield createPipeline(helper, controller, w, channels)

    new Operator[F, T, Unit](pipeline)
  }

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
      check <- if (cfg.checkOpenshiftOnStartup) OperatorUtils.checkKubeEnv(client) else none[Boolean].pure[F]
    } yield check
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
        case (signal, _) => signal
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

  def start: F[(F[ExitCode], CloseableWatcher)] =
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
      workers = reconcilerInterval.fold(consumer)(
        i => F.race(consumer, runReconciler(i, pipe, kind, namespace)).map(_.merge)
      )
    } yield (workers, closableWatcher)).onError {
      case ex: Throwable =>
        F.delay(logger.error(s"Could not to start operator", ex))
    }

  private def runReconciler(
    interval: FiniteDuration,
    pipe: OperatorPipeline[F, T, U],
    kind: String,
    namespace: K8sNamespace
  ): F[ReconcilerExitCode] = {
    val r = new Reconciler[F, T, U](interval, pipe.channels, F.delay(pipe.helper.currentResources))
    F.delay(logger.info(s"${gr}Starting reconciler $kind$xx in namespace '$namespace' with $interval interval")) *>
      r.run.guaranteeCase {
        case Canceled => F.delay(logger.debug("Reconciler was canceled!"))
        case _ => F.unit
      }
  }
}
