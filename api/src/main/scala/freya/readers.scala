package freya

trait Reader[T] {
  def targetClass: Class[T]
}

trait YamlReader[T] extends Reader[T] {
  def fromString(yaml: String): Either[Throwable, T]
}

trait JsonReader[T] extends Reader[T] {
  def fromString(json: String): Either[Throwable, T]
}

object JsonReader {
  implicit val unitRead: JsonReader[Unit] = new JsonReader[Unit] {
    override def fromString(json: String): Either[Throwable, Unit] = Left(new RuntimeException("impossible"))

    override def targetClass: Class[Unit] = classOf[Unit]
  }
}
