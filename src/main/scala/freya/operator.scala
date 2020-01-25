package freya

import cats.effect.ExitCase.Canceled
import cats.effect.concurrent.MVar
import cats.effect.syntax.all._
import cats.effect.{Concurrent, ConcurrentEffect, ExitCode, Resource, Sync, Timer}
import cats.implicits._
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.scalalogging.LazyLogging
import freya.Configuration.{ConfigMapConfig, CrdConfig}
import freya.ExitCodes.{ConsumerExitCode, OperatorExitCode, ReconcilerExitCode}
import freya.Retry.{Infinite, Times}
import freya.errors.OperatorError
import freya.internal.AnsiColors._
import freya.internal.OperatorUtils._
import freya.internal.Reconciler
import freya.internal.crd.Deployer
import freya.models.CustomResource
import freya.resource.{ConfigMapParser, CrdParser, Labels}
import freya.watcher.AbstractWatcher.{Channel, CloseableWatcher}
import freya.watcher.FeedbackConsumer.FeedbackChannel
import freya.watcher._
import freya.watcher.actions.OperatorAction
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client._
import io.fabric8.kubernetes.client.utils.Serialization

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.reflect.ClassTag
import scala.util.Random

trait CrdWatchMaker[F[_], T, U] {
  def make(context: CrdWatcherContext[F, T, U]): WatcherMaker[F]
}

object CrdWatchMaker {
  implicit def crd[F[_]: ConcurrentEffect, T, U]: CrdWatchMaker[F, T, U] =
    (context: CrdWatcherContext[F, T, U]) => new CustomResourceWatcher(context)
}

trait ConfigMapWatchMaker[F[_], T] {
  def make(context: ConfigMapWatcherContext[F, T]): WatcherMaker[F]
}

object ConfigMapWatchMaker {
  implicit def cm[F[_]: ConcurrentEffect, T]: ConfigMapWatchMaker[F, T] =
    (context: ConfigMapWatcherContext[F, T]) => new ConfigMapWatcher(context)
}

trait CrdDeployer[F[_], T] {
  def deployCrd(client: KubernetesClient, cfg: CrdConfig, isOpenShift: Option[Boolean]): F[CustomResourceDefinition]
}

object CrdDeployer {
  implicit def deployer[F[_]: Sync, T]: CrdDeployer[F, T] =
    (client: KubernetesClient, cfg: CrdConfig, isOpenShift: Option[Boolean]) =>
      Deployer.deployCrd(client, cfg, isOpenShift)
}

trait FeedbackConsumerMaker[F[_], T, U] {
  def make(
    client: KubernetesClient,
    crd: CustomResourceDefinition,
    channel: FeedbackChannel[F, T, U],
    kind: String,
    apiVersion: String
  ): FeedbackConsumerAlg[F]
}

object FeedbackConsumerMaker {
  implicit def consumer[F[_]: ConcurrentEffect, T, U]: FeedbackConsumerMaker[F, T, U] =
    (
      client: KubernetesClient,
      crd: CustomResourceDefinition,
      channel: FeedbackChannel[F, T, U],
      kind: String,
      apiVersion: String
    ) => new FeedbackConsumer[F, T, U](client, crd, channel, kind, apiVersion)
}

object Operator extends LazyLogging {

  def ofCrd[F[_]: ConcurrentEffect: Timer, T: ClassTag, U: ClassTag](
    cfg: CrdConfig,
    client: F[KubernetesClient],
    controller: Controller[F, T, U]
  )(
    implicit watch: CrdWatchMaker[F, T, U],
    helper: CrdHelperMaker[F, T, U],
    deployer: CrdDeployer[F, T],
    consumer: FeedbackConsumerMaker[F, T, U]
  ): Operator[F, T, U] =
    ofCrd[F, T, U](cfg, client)((_: CrdHelper[F, T, U]) => controller)

  def ofCrd[F[_], T: ClassTag, U: ClassTag](cfg: CrdConfig, client: F[KubernetesClient])(
    controller: CrdHelper[F, T, U] => Controller[F, T, U]
  )(
    implicit F: ConcurrentEffect[F],
    T: Timer[F],
    watch: CrdWatchMaker[F, T, U],
    helperMaker: CrdHelperMaker[F, T, U],
    deployer: CrdDeployer[F, T],
    consumer: FeedbackConsumerMaker[F, T, U]
  ): Operator[F, T, U] = {

    val pipeline = for {
      c <- client
      isOpenShift <- checkEnvAndConfig[F, T](c, cfg)
      crd <- deployer.deployCrd(c, cfg, isOpenShift)
      channel <- newActionChannel[F, T, U]
      feedback <- newFeedbackChannel[F, T, U]
      parser <- CrdParser()

      helper = {
        val context = CrdHelperContext(cfg, c, isOpenShift, crd, parser)
        helperMaker.make(context)
      }
      ctl = controller(helper)

      context = CrdWatcherContext(
        cfg.namespace,
        cfg.getKind[T],
        new ActionConsumer[F, T, U](ctl, cfg.getKind[T], feedback),
        consumer.make(c, crd, feedback, cfg.getKind[T], cfg.apiVersion),
        CrdHelper.convertCr[T, U](cfg.kindClass[T], parser),
        channel,
        c,
        crd
      )

      w <- F.delay(watch.make(context).watch)
    } yield createPipeline(helper, ctl, w, channel)

    new Operator[F, T, U](pipeline)
  }

  private def newFeedbackChannel[F[_]: ConcurrentEffect, T, U: ClassTag] =
    MVar[F].empty[Either[Unit, CustomResource[T, U]]]

  def ofConfigMap[F[_]: ConcurrentEffect: Timer, T: ClassTag](
    cfg: ConfigMapConfig,
    client: F[KubernetesClient],
    controller: CmController[F, T]
  )(implicit watchMaker: ConfigMapWatchMaker[F, T], helper: ConfigMapHelperMaker[F, T]): Operator[F, T, Unit] =
    ofConfigMap[F, T](cfg, client)((_: ConfigMapHelper[F, T]) => controller)

  def ofConfigMap[F[_], T: ClassTag](cfg: ConfigMapConfig, client: F[KubernetesClient])(
    controller: ConfigMapHelper[F, T] => CmController[F, T]
  )(
    implicit F: ConcurrentEffect[F],
    T: Timer[F],
    watchMaker: ConfigMapWatchMaker[F, T],
    helperMaker: ConfigMapHelperMaker[F, T]
  ): Operator[F, T, Unit] = {

    val pipeline = for {
      k8sClient <- client
      isOpenShift <- checkEnvAndConfig(k8sClient, cfg)
      channel <- newActionChannel[F, T, Unit]
      feedback <- newFeedbackChannel[F, T, Unit]
      parser <- ConfigMapParser()

      helper = {
        val context = ConfigMapHelperContext(cfg, k8sClient, isOpenShift, parser)
        helperMaker.make(context)
      }
      ctl = controller(helper)
      context = ConfigMapWatcherContext(
        cfg.namespace,
        cfg.getKind,
        ctl,
        new ActionConsumer[F, T, Unit](ctl, cfg.getKind[T], feedback),
        ConfigMapHelper.convertCm(cfg.kindClass, parser),
        channel,
        k8sClient,
        Labels.forKind(cfg.getKind, cfg.prefix)
      )

      w <- F.delay(watchMaker.make(context).watch)
    } yield createPipeline(helper, ctl, w, channel)

    new Operator[F, T, Unit](pipeline)
  }

  private def newActionChannel[F[_]: Concurrent, T, U]: F[Channel[F, T, U]] =
    MVar[F].empty[Either[OperatorError, OperatorAction[T, U]]]

  private def createPipeline[F[_]: ConcurrentEffect, T, U](
    helper: AbstractHelper[F, T, U],
    controller: Controller[F, T, U],
    watcher: F[(CloseableWatcher, F[ConsumerExitCode])],
    channel: Channel[F, T, U]
  ) =
    OperatorPipeline[F, T, U](helper, watcher, channel, controller.onInit())

  private def checkEnvAndConfig[F[_]: Sync, T: ClassTag](
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
  channel: Channel[F, T, U],
  onInit: F[Unit]
)

class Operator[F[_], T, U] private (
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
      _ <- F.delay(Serialization.jsonMapper().registerModule(DefaultScalaModule))

      name = pipe.helper.cfg.getKind
      namespace = pipe.helper.targetNamespace

      _ <- F.delay(logger.info(s"Starting operator $ye$name$xx for namespace $namespace"))
      _ <- pipe.onInit
      (closableWatcher, consumer) <- pipe.consumer
      _ <- F
        .delay(
          logger
            .info(s"${gr}Operator $name was started$xx in namespace '$namespace'")
        )
      reconciler = runReconciler(pipe, name, namespace)
    } yield (F.race(consumer, reconciler), closableWatcher)).onError {
      case ex: Throwable =>
        F.delay(logger.error(s"Unable to start operator", ex))
    }

  private def runReconciler(pipe: OperatorPipeline[F, T, U], name: String, namespace: K8sNamespace) =
    reconcilerInterval match {
      case None => F.never[ReconcilerExitCode]
      case Some(i) =>
        val r = new Reconciler[F, T, U](i, pipe.channel, F.delay(pipe.helper.currentResources))
        F.delay(logger.info(s"${gr}Starting reconciler $name$xx in namespace '$namespace' with $i interval")) *>
          r.run.guaranteeCase {
            case Canceled => F.delay(logger.debug("Reconciler was canceled!"))
            case _ => F.unit
          }
    }
}
