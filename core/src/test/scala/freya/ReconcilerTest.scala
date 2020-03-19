package freya

import cats.effect.IO
import cats.effect.concurrent.MVar
import freya.ExitCodes.ConsumerExitCode
import freya.internal.Reconciler
import freya.models.Resource
import freya.watcher.AbstractWatcher.Channel
import freya.watcher.{ActionConsumer, Channels, FeedbackConsumerAlg}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class ReconcilerTest extends AnyFlatSpec with Matchers {

  it should "be able to stop" in {
    ///given
    val channels = createChannels
    val r = new Reconciler[IO, Kerb, Status](5.seconds, channels, IO(Right(List.empty[Resource[Kerb, Status]])))
    val io = r.run

    //when
    val res = IO.race(io, IO.sleep(0.milliseconds)).unsafeRunSync()
    //then
    res should ===(Right(()))
  }

  it should "return exit code" in {
    //given
    val channels = createChannels
    val r =
      new Reconciler[IO, Kerb, Status](0.millis, channels, IO.raiseError(new RuntimeException("test exception")))
    val io = r.run
    //when
    val res = IO.race(io, IO.sleep(1.minute)).unsafeRunSync()
    //then
    res should ===(Left((ExitCodes.ReconcileExitCode)))
  }

  private def createChannels: Channels[IO, Kerb, Status] = {
    val queue = Operator.namespaceQueue[IO, Kerb, Status]
    new Channels(
      MVar[IO].empty[ConsumerExitCode].unsafeRunSync(),
      (namespace: String, ch: Channel[IO, Kerb, Status], feedback: Option[FeedbackConsumerAlg[IO, Status]]) =>
        new ActionConsumer[IO, Kerb, Status](namespace, null, "", queue, 0, ch, feedback),
      () => None
    )
  }
}
