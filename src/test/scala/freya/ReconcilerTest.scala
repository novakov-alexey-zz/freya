package freya

import cats.effect.IO
import cats.effect.concurrent.MVar
import freya.errors.OperatorError
import freya.internal.Reconciler
import freya.models.Resource
import freya.watcher.actions.OperatorAction
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class ReconcilerTest extends AnyFlatSpec with Matchers {

  it should "be able to stop" in {
    ///given
    val channel = MVar[IO].empty[Either[OperatorError, OperatorAction[Kerb, Status]]].unsafeRunSync()
    val r = new Reconciler[IO, Kerb, Status](5.seconds, channel, IO(Right(List.empty[Resource[Kerb, Status]])))
    val io = r.run

    //when
    val res = IO.race(io, IO.sleep(0.milliseconds)).unsafeRunSync()
    //then
    res should ===(Right(()))
  }

  it should "return exit code" in {
    //given
    val channel = MVar[IO].empty[Either[OperatorError, OperatorAction[Kerb, Status]]].unsafeRunSync()
    val r =
      new Reconciler[IO, Kerb, Status](0.millis, channel, IO.raiseError(new RuntimeException("test exception")))
    val io = r.run
    //when
    val res = IO.race(io, IO.sleep(1.minute)).unsafeRunSync()
    //then
    res should ===(Left((ExitCodes.ReconcileExitCode)))
  }
}
