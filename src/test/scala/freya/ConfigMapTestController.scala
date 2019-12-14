package freya

import cats.effect.ConcurrentEffect
import io.fabric8.kubernetes.api.model.ConfigMap

class ConfigMapTestController[F[_]: ConcurrentEffect] extends CrdTestController[F] with CmController {
  override def isSupported(cm: ConfigMap): Boolean = true
}
