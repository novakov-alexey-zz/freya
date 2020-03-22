package freya

import cats.effect.IO
import cats.effect.concurrent.MVar
import cats.implicits._
import freya.watcher.BlockingQueue
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.collection.mutable.ListBuffer

class BlockingQueueTest extends AnyPropSpec with Matchers with ScalaCheckPropertyChecks with Eventually {

  property("consumer receives all produced values") {
    forAll { (size: Int, name: String, l: List[String]) =>
      whenever(size > 0 && l.nonEmpty) {
        val q = BlockingQueue[IO, String](size, name, MVar.empty[IO, Unit].unsafeRunSync())
        val consumer = ListBuffer.empty[String]
        l.map(q.produce).sequence.unsafeRunAsyncAndForget()
        q.consume {
          s => IO(consumer += s) >> IO(if (consumer.length < l.length) true else false)
        }.unsafeRunSync()
        consumer.length should ===(l.length)
      }
    }
  }
}
