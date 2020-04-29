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
    forAll { (size: Int, name: String, produced: List[String]) =>
      whenever(size > 0 && produced.nonEmpty) {
        val queue = BlockingQueue[IO, String](size, name, MVar.empty[IO, Unit].unsafeRunSync())
        val consumed = ListBuffer.empty[String]
        val producer = produced.map(queue.produce).sequence
        val consumer = queue.consume(s => IO(consumed += s) >> IO(consumed.length < produced.length))
        (producer, consumer).parMapN { (_, _) => () }.unsafeRunSync()
        consumed.length should ===(produced.length)
        consumed should ===(produced)
      }
    }
  }
}
