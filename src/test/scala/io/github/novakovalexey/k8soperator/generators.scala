package io.github.novakovalexey.k8soperator

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.{DefaultScalaModule, ScalaObjectMapper}
import io.fabric8.kubernetes.api.model.{ConfigMap, ConfigMapBuilder, ObjectMeta, ObjectMetaBuilder}
import io.github.novakovalexey.k8soperator.watcher._
import io.fabric8.kubernetes.client.Watcher.Action
import org.scalacheck.{Arbitrary, Gen}

import scala.jdk.CollectionConverters._
import generators.arbitrary

object generators {
  def arbitrary[T](implicit a: Arbitrary[T]): Gen[T] = a.arbitrary
}

object ObjectMetaTest {
  def apply(name: String, namespace: String): ObjectMeta =
    new ObjectMetaBuilder().withName(name).withNamespace(namespace).build()

  val maxStrGen: Int => Gen[String] = (max: Int) =>
    Gen.alphaStr.map(str => if (str.length <= max) str else str.substring(0, max)).suchThat(_.nonEmpty)

  def gen: Gen[ObjectMeta] =
    for {
      name <- maxStrGen(63)
      namespace <- maxStrGen(63)
    } yield ObjectMetaTest(name, namespace)
}

object InfoClass {

  def gen[T: Arbitrary]: Gen[InfoClass[T]] =
    for {
      spec <- arbitrary[T]
      meta <- ObjectMetaTest.gen
    } yield {
      val ic = new InfoClass[T]
      ic.setSpec(spec)
      ic.setMetadata(meta)
      ic.setApiVersion("io.github.novakov-alexey/v1")
      ic
    }
}

object WatcherAction {
  def gen: Gen[Action] =
    Gen.oneOf(Action.ADDED, Action.DELETED, Action.MODIFIED)
}

object CM {
  val mapper = new ObjectMapper(new YAMLFactory()) with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)

  def gen[T](implicit A: Arbitrary[T]): Gen[ConfigMap] =
    for {
      spec <- Arbitrary.arbitrary[T]
      meta <- ObjectMetaTest.gen
    } yield {

      new ConfigMapBuilder()
        .withMetadata(meta)
        .withData(Map("config" -> mapper.writeValueAsString(spec)).asJava)
        .build()
    }
}
