package io.github.novakovalexey.k8soperator4s.common

import java.util


/**
 * Operator configuration
 */
object OperatorConfig {
  val WATCH_NAMESPACE = "WATCH_NAMESPACE"
  val SAME_NAMESPACE = "~"
  val ALL_NAMESPACES = "*"
  val METRICS = "METRICS"
  val METRICS_JVM = "METRICS_JVM"
  val METRICS_PORT = "METRICS_PORT"
  val FULL_RECONCILIATION_INTERVAL_S = "FULL_RECONCILIATION_INTERVAL_S"
  val OPERATOR_OPERATION_TIMEOUT_MS = "OPERATOR_OPERATION_TIMEOUT_MS"
  val DEFAULT_METRICS = true
  val DEFAULT_METRICS_JVM = false
  val DEFAULT_METRICS_PORT = 8080
  val DEFAULT_FULL_RECONCILIATION_INTERVAL_S: Long = 180
  val DEFAULT_OPERATION_TIMEOUT_MS: Long = 60_000

  /**
   * Loads configuration parameters from a related map
   *
   * @param map map from which loading configuration parameters
   * @return Cluster Operator configuration instance
   */
  def fromMap(map: util.Map[String, String]): OperatorConfig = {
    val namespacesList = map.get(WATCH_NAMESPACE)
    var namespaces = Set.empty[String]
    if (namespacesList == null || namespacesList.isEmpty) { // empty WATCH_NAMESPACE means we will be watching all the namespaces
      namespaces = Set(ALL_NAMESPACES)
    } else {
      namespaces = namespacesList.trim.split("\\s*,+\\s*").toSet
      namespaces = namespaces.map((ns: String) => if (ns.startsWith("\"") && ns.endsWith("\"")) ns.substring(1, ns.length - 1) else ns)
    }

    var metricsAux = DEFAULT_METRICS
    val metricsEnvVar = map.get(METRICS)
    if (metricsEnvVar != null) metricsAux = !("false" == metricsEnvVar.trim.toLowerCase)
    var metricsJvmAux = DEFAULT_METRICS_JVM
    var metricsPortAux = DEFAULT_METRICS_PORT
    if (metricsAux) {
      val metricsJvmEnvVar = map.get(METRICS_JVM)
      if (metricsJvmEnvVar != null) metricsJvmAux = "true" == metricsJvmEnvVar.trim.toLowerCase
      val metricsPortEnvVar = map.get(METRICS_PORT)
      if (metricsPortEnvVar != null) metricsPortAux = metricsPortEnvVar.trim.toLowerCase.toInt
    }
    var reconciliationInterval = DEFAULT_FULL_RECONCILIATION_INTERVAL_S
    val reconciliationIntervalEnvVar = map.get(FULL_RECONCILIATION_INTERVAL_S)
    if (reconciliationIntervalEnvVar != null)
      reconciliationInterval = reconciliationIntervalEnvVar.toLong

    var operationTimeout = DEFAULT_OPERATION_TIMEOUT_MS
    val operationTimeoutEnvVar = map.get(OPERATOR_OPERATION_TIMEOUT_MS)

    if (operationTimeoutEnvVar != null) operationTimeout = operationTimeoutEnvVar.toLong

    new OperatorConfig(namespaces, metricsAux, metricsJvmAux, metricsPortAux, reconciliationInterval, operationTimeout)
  }
}


/**
 * Constructor
 *
 * @param namespaces              namespace in which the operator will run and create resources
 * @param metrics                 whether the metrics server for prometheus should be started
 * @param metricsJvm              whether to expose the internal JVM metrics, like heap, # of threads, etc.
 * @param metricsPort             on which port the metrics server should be listening
 * @param reconciliationIntervalS specify every how many milliseconds the reconciliation runs
 * @param operationTimeoutMs      timeout for internal operations specified in milliseconds
 */
case class OperatorConfig(namespaces: Set[String], metrics: Boolean, metricsJvm: Boolean, metricsPort: Int, reconciliationIntervalS: Long, operationTimeoutMs: Long)

