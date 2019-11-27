package io.github.novakovalexey.k8soperator

import cats.effect.concurrent.MVar
import cats.effect.{ConcurrentEffect, ExitCode, Resource, Sync, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.{Watch, _}
import io.github.novakovalexey.k8soperator.OperatorUtils._
import io.github.novakovalexey.k8soperator.common.AbstractOperator.getKind
import io.github.novakovalexey.k8soperator.common.AnsiColors._
import io.github.novakovalexey.k8soperator.common._
import io.github.novakovalexey.k8soperator.common.watcher.WatchMaker.ConsumerSignal
import io.github.novakovalexey.k8soperator.common.watcher.actions.OperatorAction
import io.github.novakovalexey.k8soperator.common.watcher.{ConfigMapWatcher, CrdWatcherContext, CustomResourceWatcher, WatchMaker}
import io.github.novakovalexey.k8soperator.errors.OperatorError
import io.github.novakovalexey.k8soperator.resource.{CrdParser, Labels}

import scala.annotation.unused
import scala.concurrent.duration._

trait CrdWatchMaker[F[_], T] {
  def make(context: CrdWatcherContext[F, T]): WatchMaker[F]
}

trait CrdDeployer[F[_], T] {
  def deployCrd(client: KubernetesClient, cfg: CrdConfig[T], isOpenShift: Option[Boolean]): F[CustomResourceDefinition]
}

object CrdDeployer {
  implicit def deployer[F[_]: Sync, T]: CrdDeployer[F, T] =
    (client: KubernetesClient, cfg: CrdConfig[T], isOpenShift: Option[Boolean]) =>
      CrdOperator.deployCrd(client, cfg, isOpenShift)
}

object CrdWatchMaker {
  implicit def crd[F[_]: ConcurrentEffect, T]: CrdWatchMaker[F, T] =
    (context: CrdWatcherContext[F, T]) => new CustomResourceWatcher(context)
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

  def ofConfigMap[F[_], T](
    controller: ConfigMapOperator[F, T] => ConfigMapController[F, T],
    cfg: ConfigMapConfig[T],
    client: F[KubernetesClient]
  )(implicit F: ConcurrentEffect[F]): Operator[F, T] = {

    val pipeline = for {
      c <- client
      isOpenShift <- checkEnvAndConfig(c, cfg)
      channel <- MVar[F].empty[Either[OperatorError[T], OperatorAction[T]]]

      op = new ConfigMapOperator[F, T](cfg, c, isOpenShift)
      ctl = controller(op)

      w <- F.delay(
        new ConfigMapWatcher[F, T](
          cfg.namespace,
          getKind[T](cfg),
          ctl,
          ConfigMapOperator.convertCm(cfg.forKind),
          channel,
          c,
          Labels.forKind(getKind[T](cfg), cfg.prefix)
        ).watch
      )
    } yield createPipeline(op, ctl, w)

    new Operator[F, T](pipeline)
  }

  private def createPipeline[T, F[_]](
    op: AbstractOperator[F, T],
    ctl: Controller[F, T],
    w: F[(Watch, ConsumerSignal[F])]
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
  consumer: F[(Watch, ConsumerSignal[F])],
  onInit: F[Unit]
)

final case class Retry(times: Int = 1, delay: FiniteDuration = 1.second, multiplier: Int = 1)

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
        case (consumerSignal, _) =>
          consumerSignal.map(s => if (s != 0) ExitCode(s) else ExitCode.Success)
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
      (watch, consumer) <- pipe.consumer
      _ <- F
        .delay(
          logger
            .info(s"${gr}Operator $name was started$xx in namespace '$namespace'")
        )
        .onError {
          case ex: Throwable =>
            F.delay(logger.error(s"Unable to start operator for $namespace namespace", ex))
        }
    } yield (consumer, stopHandler(watch))

  private def stopHandler(watch: Watch): StopHandler =
    () => F.delay(watch.close())
}
