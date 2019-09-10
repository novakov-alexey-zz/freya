package io.github.novakovalexey.k8soperator4s.resource

import io.fabric8.kubernetes.api.model.HasMetadata

/**
 * A helper for parsing the {@code metadata.labels} section inside the K8s resource
 */
object LabelsHelper {

  /**
   * The kind of a ConfigMap:
   * <ul>
   * <li>{@code radanalytics.io/kind=cluster}
   * identifies a ConfigMap that is intended to be consumed by
   * the cluster operator.</li>
   * <li>{@code radanalytics.io/kind=app}
   * identifies a ConfigMap that is intended to be consumed
   * by the app operator.</li>
   * <li>{@code radanalytics.io/kind=notebook}
   * identifies a ConfigMap that is intended to be consumed
   * by the notebook operator.</li>
   * </ul>
   */
  val OPERATOR_KIND_LABEL = "kind"
  val OPERATOR_SEVICE_TYPE_LABEL = "service"
  val OPERATOR_RC_TYPE_LABEL = "rcType"
  val OPERATOR_POD_TYPE_LABEL = "podType"
  val OPERATOR_DEPLOYMENT_LABEL = "deployment"

  def getKind(resource: HasMetadata, prefix: String): Option[String] =
    Option(resource)
      .map(_.getMetadata.getLabels.get(prefix + OPERATOR_KIND_LABEL))

  def forKind(kind: String, prefix: String): Map[String, String] =
    Map(prefix + OPERATOR_KIND_LABEL -> kind)
}
