package freya

import cats.effect.IO
import cats.implicits._
import freya.watcher.BlockingQueue
import org.scalacheck.Gen
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

class BlockingQueueTest extends AnyPropSpec with Matchers with ScalaCheckPropertyChecks with Eventually {

  property("consumer receives all producer's values running in parallel") {
    forAll(Gen.choose(1, 100), Gen.alphaLowerStr, Gen.nonEmptyListOf(Gen.alphaNumStr)) {
      (size: Int, name: String, produced: List[String]) =>
        val queue = createQueue(size, name)
        val consumed = ListBuffer.empty[String]
        val producer = produced.map(queue.produce).sequence_
        val consumer = queue.consume(s => drain(produced.length, consumed, s))
        (producer, consumer).parMapN { (_, _) => () }.unsafeRunSync()
        check(produced, consumed)
    }
  }

  property("consumer receives all producer's values running one by one") {
    forAll(Gen.choose(1, 100), Gen.alphaLowerStr) { (capacity: Int, name: String) =>
      val produced = Gen.choose(1, capacity).flatMap(n => Gen.listOfN(n, Gen.alphaNumStr)).sample.getOrElse(Nil)
      val queue = createQueue(capacity, name)
      val consumed = ListBuffer.empty[String]
      val producer = produced.map(queue.produce).sequence_
      val consumer = queue.consume(s => drain(produced.length, consumed, s))
      producer.unsafeRunSync()
      consumer.unsafeRunSync()
      check(produced, consumed)
    }
  }

  property("multiple consumers receive all produced values running in parallel") {
    forAll(Gen.choose(1, 100), Gen.alphaLowerStr, Gen.nonEmptyListOf(Gen.alphaNumStr)) {
      (capacity: Int, name: String, produced: List[String]) =>
        val queue = createQueue(capacity, name)
        val consumed1 = ListBuffer.empty[String]
        val consumed2 = ListBuffer.empty[String]
        val producer = produced.map(queue.produce).sequence_
        val consumer1 = queue.consume(elem => drain(produced.length - consumed2.length, consumed1, elem))
        val consumer2 = queue.consume(elem => drain(produced.length - consumed1.length, consumed2, elem))
        (producer, IO.race(consumer1, consumer2)).parMapN { (_, _) => () }.unsafeRunTimed(2.seconds)
        withClue(
          s"===========================================${produced.length} = ${consumed1.length} : ${consumed2.length}"
        ) {
          (consumed1.length + consumed2.length) should ===(produced.length)
          (consumed1 ++ consumed2).toSet should ===(produced.toSet)
        }
    }
  }

  private def drain(produced: Int, storage: ListBuffer[String], elem: String) =
    IO {
      storage += elem
      storage.length < produced
    }

  private def check(produced: List[String], consumed: ListBuffer[String]) = {
    consumed.length should ===(produced.length)
    consumed.toList should ===(produced)
  }

  private def createQueue(capacity: Int, name: String) =
    BlockingQueue.create[IO, String](capacity, name).unsafeRunSync()
}
