package io.github.novakovalexey.k8soperator4s.resource

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.ConfigMap
import io.github.novakovalexey.k8soperator4s.common.Metadata
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor
import org.yaml.snakeyaml.error.YAMLException

import scala.util.{Failure, Success, Try}

/**
 * A helper for parsing the data section inside the K8s resource (ConfigMap).
 * Type parameter T represents the concrete EntityInfo that captures the configuration obout the
 * objects in the clusters we are interested in, be it spark clusters, http servers, certificates, etc.
 *
 * One can create arbitrarily deep configurations by nesting the types in <code>Class&lt;T&gt;</code> and using
 * the Snake yaml or other library as for conversions between YAML and Java objects.
 */
object HasDataHelper extends LazyLogging {

  def parseYaml[T](clazz: Class[T], yamlDoc: String): T = {
    val snake = new Yaml(new Constructor(clazz))

    Try(snake.load[T](yamlDoc)).orElse(Try(clazz.getDeclaredConstructor().newInstance())) match {
      case Success(v) =>
        Option(v).getOrElse(null.asInstanceOf[T])
      case e @ Failure(_: InstantiationException | _: IllegalAccessException) =>
        logger.error("failed to create new instance", e.exception)
        null.asInstanceOf[T]
      case Failure(e: YAMLException) =>
        val msg =
          s"""Unable to parse yaml definition of configmap, check if you don't have typo:
             |'
             |$yamlDoc
             |'
             |""".stripMargin
        logger.error(msg)
        throw new IllegalStateException(e)
      case Failure(e) =>
        logger.error("unexpected error", e)
        throw e
    }
  }

  /**
   *
   * @param clazz concrete class of type T which is monitored by the operator.
   *              This is the resulting type, we are converting into.
   * @param cm    input config map that will be converted into T.
   *              We assume there is a multi-line section in the config map called config and it
   *              contains a YAML structure that represents the object of type T. In other words the
   *              keys in the yaml should be the same as the field names in the class T and the name of the
   *              configmap will be assigned to the name of the object T. One can create arbitrarily deep
   *              configuration by nesting the types in T and using the Snake yaml as the conversion library.
   * @param T    type parameter T which represent spec of monitored object
   * @return object of type T
   */
  def parseCM[T](clazz: Class[T], cm: ConfigMap): (T, Metadata) = {
    val yaml = cm.getData.get("config")
    val meta = Metadata(cm.getMetadata.getName, cm.getMetadata.getNamespace)
    val entity = parseYaml(clazz, yaml)
    (entity, meta)
  }
}
