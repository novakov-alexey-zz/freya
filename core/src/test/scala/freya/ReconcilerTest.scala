package freya

import cats.effect.IO
import freya.internal.Reconciler
import freya.models.Resource
import freya.watcher.AbstractWatcher.Action
import freya.watcher.{ActionConsumer, BlockingQueue, Channels, FeedbackConsumerAlg}
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
      new Reconciler[IO, Kerb, Status](0.millis, channels, IO.raiseError(TestException("test exception")))
    val io = r.run
    //when
    val res = IO.race(io, IO.sleep(1.minute)).unsafeRunSync()
    //then
    res should ===(Left((ExitCodes.ReconcileExitCode)))
  }

  private def createChannels: Channels[IO, Kerb, Status] = {
    new Channels(true, (namespace: String, feedback: Option[FeedbackConsumerAlg[IO, Status]]) => {
      val queue = BlockingQueue.create[IO, Action[Kerb, Status]](5, namespace)
      queue.map(q => new ActionConsumer[IO, Kerb, Status](namespace, new CrdTestController, "", q, feedback))
    }, () => None)
  }
}
