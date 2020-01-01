package freya

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.{DefaultScalaModule, ScalaObjectMapper}
import freya.generators.arbitrary
import freya.resource.ConfigMapParser
import freya.watcher._
import io.fabric8.kubernetes.api.model.{ConfigMap, ConfigMapBuilder, ObjectMeta, ObjectMetaBuilder}
import io.fabric8.kubernetes.client.Watcher.Action
import org.scalacheck.{Arbitrary, Gen}

import scala.jdk.CollectionConverters._

object generators {
  def arbitrary[T](implicit a: Arbitrary[T]): Gen[T] = a.arbitrary
}

object ObjectMetaTest {
  def apply(name: String, namespace: String, labels: Map[String, String]): ObjectMeta =
    new ObjectMetaBuilder().withName(name).withNamespace(namespace).withLabels(labels.asJava).build()

  val maxStrGen: Int => Gen[String] = (max: Int) =>
    Gen.alphaLowerStr.map(str => if (str.length <= max) str else str.substring(0, max)).suchThat(_.nonEmpty)

  def gen: Gen[ObjectMeta] = gen(Map.empty)

  def gen(labels: Map[String, String]): Gen[ObjectMeta] =
    for {
      name <- maxStrGen(63)
      namespace <- maxStrGen(63)
    } yield ObjectMetaTest(name, namespace, labels)
}

object SpecClass {

  def gen[T: Arbitrary](kind: String): Gen[SpecClass] =
    for {
      spec <- arbitrary[T]
      meta <- ObjectMetaTest.gen
    } yield {
      val sc = new SpecClass
      sc.setApiVersion("io.github.novakov-alexey/v1")
      sc.setKind(kind)
      sc.setSpec(spec.asInstanceOf[AnyRef])
      sc.setMetadata(meta)
      sc
    }
}

object WatcherAction {
  def gen: Gen[Action] =
    Gen.oneOf(Action.ADDED, Action.DELETED, Action.MODIFIED)
}

object CM {
  val mapper = new ObjectMapper(new YAMLFactory()) with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)

  def gen[T](implicit A: Arbitrary[T]): Gen[ConfigMap] = gen[T](Map.empty[String, String])

  def genBoth[T](labels: Map[String, String])(implicit A: Arbitrary[T]): Gen[(ConfigMap, T)] =
    for {
      spec <- Arbitrary.arbitrary[T]
      meta <- ObjectMetaTest.gen(labels)
    } yield {
      (new ConfigMapBuilder()
        .withMetadata(meta)
        .withData(Map(ConfigMapParser.SpecificationKey -> mapper.writeValueAsString(spec)).asJava)
        .build(), spec)
    }

  def gen[T](labels: Map[String, String])(implicit A: Arbitrary[T]): Gen[ConfigMap] =
    genBoth[T](labels).map(_._1)
}

object Gens {
  val nonEmptyString: Gen[String] = Gen.nonEmptyListOf[Char](Gen.alphaChar).map(_.mkString)
  implicit lazy val arbBoolean: Arbitrary[Boolean] = Arbitrary(Gen.oneOf(true, false))

  def krb2: Gen[Kerb] =
    for {
      realm <- Gen.alphaUpperStr.suchThat(_.nonEmpty)
      principals <- Gen.nonEmptyListOf(principal)
      failInTest <- Arbitrary.arbitrary[Boolean]
    } yield Kerb(realm, principals, failInTest)

  def principal: Gen[Principal] =
    for {
      name <- nonEmptyString
      password <- nonEmptyString
      value <- nonEmptyString
    } yield Principal(name, password, value)
}
