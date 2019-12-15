package freya

import cats.effect.concurrent.MVar
import cats.effect.{ConcurrentEffect, ExitCode, Resource, Sync, Timer}
import cats.implicits._
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.scalalogging.LazyLogging
import freya.Controller.ConfigMapController
import freya.errors.OperatorError
import freya.internal.AnsiColors._
import freya.internal.OperatorUtils._
import freya.internal.crd.Deployer
import freya.internal.resource.{ConfigMapParser, CrdParser, Labels}
import freya.watcher.WatcherMaker.{Consumer, ConsumerSignal}
import freya.watcher._
import freya.watcher.actions.OperatorAction
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client._
import io.fabric8.kubernetes.client.utils.Serialization

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
      Deployer.deployCrd(client, cfg, isOpenShift)
}

object Operator extends LazyLogging {

  def ofCrd[F[_], T](
    cfg: CrdConfig[T],
    client: F[KubernetesClient],
    controller: Controller[F, T]
  )(implicit @unused F: ConcurrentEffect[F], W: CrdWatchMaker[F, T], D: CrdDeployer[F, T]): Operator[F, T] =
    ofCrd[F, T](cfg, client)((_: CrdHelper[F, T]) => controller)

  def ofCrd[F[_], T](cfg: CrdConfig[T], client: F[KubernetesClient])(
    controller: CrdHelper[F, T] => Controller[F, T]
  )(implicit F: ConcurrentEffect[F], W: CrdWatchMaker[F, T], D: CrdDeployer[F, T]): Operator[F, T] = {

    val pipeline = for {
      c <- client
      isOpenShift <- checkEnvAndConfig(c, cfg)
      crd <- D.deployCrd(c, cfg, isOpenShift)
      channel <- MVar[F].empty[Either[OperatorError[T], OperatorAction[T]]]
      parser <- CrdParser()

      helper = new CrdHelper[F, T](cfg, c, isOpenShift, crd, parser)
      ctl = controller(helper)
      context = CrdWatcherContext(
        cfg.namespace,
        cfg.getKind,
        ctl,
        CrdHelper.convertCr(cfg.forKind, parser),
        channel,
        c,
        crd
      )

      w <- F.delay(W.make(context).watch)
    } yield createPipeline(helper, ctl, w)

    new Operator[F, T](pipeline)
  }

  def ofConfigMap[F[_]: ConcurrentEffect, T](
    cfg: ConfigMapConfig[T],
    client: F[KubernetesClient],
    controller: ConfigMapController[F, T]
  )(implicit W: ConfigMapWatchMaker[F, T]): Operator[F, T] =
    ofConfigMap[F, T](cfg, client)((_: ConfigMapHelper[F, T]) => controller)

  def ofConfigMap[F[_], T](cfg: ConfigMapConfig[T], client: F[KubernetesClient])(
    controller: ConfigMapHelper[F, T] => ConfigMapController[F, T]
  )(implicit F: ConcurrentEffect[F], W: ConfigMapWatchMaker[F, T]): Operator[F, T] = {

    val pipeline = for {
      c <- client
      isOpenShift <- checkEnvAndConfig(c, cfg)
      channel <- MVar[F].empty[Either[OperatorError[T], OperatorAction[T]]]
      parser <- ConfigMapParser()

      helper = new ConfigMapHelper[F, T](cfg, c, isOpenShift, parser)
      ctl = controller(helper)
      context = ConfigMapWatcherContext(
        cfg.namespace,
        cfg.getKind,
        ctl,
        ConfigMapHelper.convertCm(cfg.forKind, parser),
        channel,
        c,
        Labels.forKind(cfg.getKind, cfg.prefix)
      )

      w <- F.delay(W.make(context).watch)
    } yield createPipeline(helper, ctl, w)

    new Operator[F, T](pipeline)
  }

  private def createPipeline[T, F[_]: ConcurrentEffect](
    op: AbstractHelper[F, T],
    ctl: Controller[F, T],
    w: F[(Consumer, ConsumerSignal[F])]
  ) =
    OperatorPipeline[F, T](op, w, ctl.onInit())

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
  helper: AbstractHelper[F, T],
  consumer: F[(Consumer, ConsumerSignal[F])],
  onInit: F[Unit]
)

class Operator[F[_], T] private (pipeline: F[OperatorPipeline[F, T]])(implicit F: ConcurrentEffect[F])
    extends LazyLogging {

  def run: F[ExitCode] =
    Resource
      .make(start)(c => F.delay(c._2.close()) *> F.delay(logger.info(s"${re}Operator stopped$xx")))
      .use(_._1)
      .recoverWith {
        case e =>
          logger.error("Got error while running an operator", e)
          ExitCode.Error.pure[F]
      }

  def withRestart(retry: Retry = Infinite())(implicit T: Timer[F]): F[ExitCode] =
    run.flatMap(loop(_, retry))

  private def loop(ec: ExitCode, retry: Retry)(implicit T: Timer[F]) = {
    val (canRestart, delay, nextRetry, remaining) = retry match {
      case Times(maxRetries, delay, multiplier) =>
        (
          maxRetries > 0,
          delay,
          F.delay[Retry](Times(maxRetries - 1, Retry.nextDelay(delay, multiplier), multiplier)),
          maxRetries.toString
        )
      case Infinite(delay, multiplier) =>
        (true, delay, F.delay[Retry](Infinite(Retry.nextDelay(delay, multiplier), multiplier)), "infinite")
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

  def start: F[(ConsumerSignal[F], Consumer)] =
    (for {
      pipe <- pipeline
      _ <- F.delay(Serialization.jsonMapper().registerModule(DefaultScalaModule))

      name = pipe.helper.cfg.getKind
      namespace = pipe.helper.targetNamespace

      _ <- F.delay(logger.info(s"Starting operator $ye$name$xx for namespace $namespace"))
      _ <- pipe.onInit
      (consumer, signal) <- pipe.consumer
      _ <- F
        .delay(
          logger
            .info(s"${gr}Operator $name was started$xx in namespace '$namespace'")
        )
    } yield (signal, consumer)).onError {
      case ex: Throwable =>
        F.delay(logger.error(s"Unable to start operator", ex))
    }
}
