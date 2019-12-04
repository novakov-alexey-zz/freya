package io.github.novakovalexey.k8soperator

import cats.effect.ConcurrentEffect
import io.fabric8.kubernetes.api.model.ConfigMap

class ConfigMapTestController[F[_]: ConcurrentEffect] extends CrdTestController[F] with CMController {
  override def isSupported(cm: ConfigMap): Boolean = true
}
