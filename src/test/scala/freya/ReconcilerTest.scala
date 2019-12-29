package freya

import cats.effect.IO
import cats.effect.concurrent.MVar
import freya.errors.OperatorError
import freya.models.Resource
import freya.watcher.actions.OperatorAction
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class ReconcilerTest extends AnyFlatSpec with IOUtils with Matchers {

  it should "be able to stop" in {
    ///given
    val channel = MVar[IO].empty[Either[OperatorError[Kerb], OperatorAction[Kerb]]].unsafeRunSync()
    val r = new Reconciler[IO, Kerb](channel, IO(List.empty[Resource[Kerb]]))
    val io = r.run()

    //when
    val res = par2(io, IO.sleep(0.milliseconds)).unsafeRunSync()
    //then
    res should ===(Right(()))
  }

  it should "return exit code" in {
    //given
    val channel = MVar[IO].empty[Either[OperatorError[Kerb], OperatorAction[Kerb]]].unsafeRunSync()
    val r = new Reconciler[IO, Kerb](channel, IO.raiseError(new RuntimeException("test exception")))
    val io = r.run(0.millis)
    //when
    val res = par2(io, IO.sleep(1.minute)).unsafeRunSync()
    //then
    res should ===(Left((signals.ReconcileExitCode)))
  }
}
