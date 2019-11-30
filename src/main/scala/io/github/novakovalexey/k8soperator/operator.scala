package io.github.novakovalexey.k8soperator

import cats.effect.concurrent.MVar
import cats.effect.{ConcurrentEffect, ExitCode, Resource, Sync, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client._
import io.github.novakovalexey.k8soperator.Controller.ConfigMapController
import io.github.novakovalexey.k8soperator.common.AbstractOperator.getKind
import io.github.novakovalexey.k8soperator.common._
import io.github.novakovalexey.k8soperator.errors.OperatorError
import io.github.novakovalexey.k8soperator.internal.AnsiColors._
import io.github.novakovalexey.k8soperator.internal.OperatorUtils._
import io.github.novakovalexey.k8soperator.internal.resource.{ConfigMapParser, CrdParser, Labels}
import io.github.novakovalexey.k8soperator.watcher.WatcherMaker.{Consumer, ConsumerSignal}
import io.github.novakovalexey.k8soperator.watcher._
import io.github.novakovalexey.k8soperator.watcher.actions.OperatorAction

import scala.annotation.unused

trait CrdWatchMaker[F[_], T] {
  def make(context: CrdWatcherContext[F, T]): WatcherMaker[F]
}

object CrdWatchMaker {
  implicit def crd[F[_]: ConcurrentEffect, T]: CrdWatchMaker[F, T] =
    (context: CrdWatcherContext[F, T]) => new CustomResourceWatcher(context)
}

trait ConfigMapWatchMaker[F[_], T] {
  def make(context: ConfigMapWatcherContext[F, T]): WatcherMaker[F]
}

object ConfigMapWatchMaker {
  implicit def cm[F[_]: ConcurrentEffect, T]: ConfigMapWatchMaker[F, T] =
    (context: ConfigMapWatcherContext[F, T]) => new ConfigMapWatcher(context)
}

trait CrdDeployer[F[_], T] {
  def deployCrd(client: KubernetesClient, cfg: CrdConfig[T], isOpenShift: Option[Boolean]): F[CustomResourceDefinition]
}

object CrdDeployer {
  implicit def deployer[F[_]: Sync, T]: CrdDeployer[F, T] =
    (client: KubernetesClient, cfg: CrdConfig[T], isOpenShift: Option[Boolean]) =>
      CrdOperator.deployCrd(client, cfg, isOpenShift)
}

object Operator extends LazyLogging {

  def ofCrd[F[_], T](
    cfg: CrdConfig[T],
    client: F[KubernetesClient],
    controller: Controller[F, T]
  )(implicit @unused F: ConcurrentEffect[F], W: CrdWatchMaker[F, T], D: CrdDeployer[F, T]): Operator[F, T] =
    ofCrd[F, T](cfg, client)((_: CrdOperator[F, T]) => controller)

  def ofCrd[F[_], T](cfg: CrdConfig[T], client: F[KubernetesClient])(
    controller: CrdOperator[F, T] => Controller[F, T]
  )(implicit F: ConcurrentEffect[F], W: CrdWatchMaker[F, T], D: CrdDeployer[F, T]): Operator[F, T] = {

    val pipeline = for {
      c <- client
      isOpenShift <- checkEnvAndConfig(c, cfg)
      crd <- D.deployCrd(c, cfg, isOpenShift)
      channel <- MVar[F].empty[Either[OperatorError[T], OperatorAction[T]]]
      parser <- CrdParser()

      operator = new CrdOperator[F, T](cfg, c, isOpenShift, crd, parser)
      ctl = controller(operator)
      context = CrdWatcherContext(
        cfg.namespace,
        getKind(cfg),
        ctl,
        CrdOperator.convertCr(cfg.forKind, parser),
        channel,
        c,
        crd
      )

      w <- F.delay(W.make(context).watch)
    } yield createPipeline(operator, ctl, w)

    new Operator[F, T](pipeline)
  }

  def ofConfigMap[F[_]: ConcurrentEffect, T](
    cfg: ConfigMapConfig[T],
    client: F[KubernetesClient],
    controller: ConfigMapController[F, T]
  )(implicit W: ConfigMapWatchMaker[F, T]): Operator[F, T] =
    ofConfigMap[F, T](cfg, client)((_: ConfigMapOperator[F, T]) => controller)

  def ofConfigMap[F[_], T](cfg: ConfigMapConfig[T], client: F[KubernetesClient])(
    controller: ConfigMapOperator[F, T] => ConfigMapController[F, T]
  )(implicit F: ConcurrentEffect[F], W: ConfigMapWatchMaker[F, T]): Operator[F, T] = {

    val pipeline = for {
      c <- client
      isOpenShift <- checkEnvAndConfig(c, cfg)
      channel <- MVar[F].empty[Either[OperatorError[T], OperatorAction[T]]]
      parser <- ConfigMapParser()

      op = new ConfigMapOperator[F, T](cfg, c, isOpenShift, parser)
      ctl = controller(op)
      context = ConfigMapWatcherContext(
        cfg.namespace,
        getKind[T](cfg),
        ctl,
        ConfigMapOperator.convertCm(cfg.forKind, parser),
        channel,
        c,
        Labels.forKind(getKind[T](cfg), cfg.prefix)
      )

      w <- F.delay(W.make(context).watch)
    } yield createPipeline(op, ctl, w)

    new Operator[F, T](pipeline)
  }

  private def createPipeline[T, F[_]](
    op: AbstractOperator[F, T],
    ctl: Controller[F, T],
    w: F[(Consumer, ConsumerSignal[F])]
  )(implicit F: ConcurrentEffect[F]) =
    OperatorPipeline[F, T](op, w, F.defer(ctl.onInit()))

  private def checkEnvAndConfig[F[_]: Sync, T](client: KubernetesClient, cfg: OperatorCfg[T]): F[Option[Boolean]] =
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

private case class OperatorPipeline[F[_], T](
  operator: AbstractOperator[F, T],
  consumer: F[(Consumer, ConsumerSignal[F])],
  onInit: F[Unit]
)

class Operator[F[_], T] private (pipeline: F[OperatorPipeline[F, T]])(implicit F: ConcurrentEffect[F])
    extends LazyLogging {

  trait StopHandler {
    def stop(): F[Unit]
  }

  def run: F[ExitCode] =
    Resource
      .make(start) {
        case (_, s) =>
          s.stop *> F.delay(logger.info(s"${re}Operator stopped$xx"))
      }
      .use {
        case (consumerSignal, _) => consumerSignal
      }

  def withRestart(retry: Retry = Retry())(implicit T: Timer[F]): F[ExitCode] =
    run.flatMap(loop(_, retry)).recoverWith {
      case e =>
        logger.error("Got error while running an operator", e)
        loop(ExitCode.Error, retry)
    }

  private def loop(ec: ExitCode, retry: Retry)(implicit T: Timer[F]) =
    if (retry.times > 0)
      for {
        _ <- F.delay(logger.info(s"Sleeping for ${retry.delay}"))
        _ <- T.sleep(retry.delay)
        _ <- F.delay(logger.info(s"${re}Going to restart$xx. Restarts left: ${retry.times}"))
        d <- F.delay(retry.delay * retry.multiplier.toLong)
        ec <- withRestart(retry.copy(retry.times - 1, delay = d))
      } yield ec
    else ec.pure[F]

  def start: F[(ConsumerSignal[F], StopHandler)] =
    for {
      pipe <- pipeline

      name = AbstractOperator.getKind(pipe.operator.cfg)
      namespace = if (pipe.operator.cfg.namespace == CurrentNamespace) pipe.operator.clientNamespace
      else pipe.operator.cfg.namespace

      _ <- F.delay(logger.info(s"Starting operator $name for namespace $namespace"))
      _ <- pipe.onInit
      (consumer, signal) <- pipe.consumer
      _ <- F
        .delay(
          logger
            .info(s"${gr}Operator $name was started$xx in namespace '$namespace'")
        )
        .onError {
          case ex: Throwable =>
            F.delay(logger.error(s"Unable to start operator for $namespace namespace", ex))
        }
    } yield (signal, stopHandler(consumer))

  private def stopHandler(consumer: Consumer): StopHandler =
    () => F.delay(consumer.close())
}
