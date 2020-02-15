package freya

trait JsonWriter[T] {
  def toString(v: T): String
}

object JsonWriter {
  implicit val unitWriter: JsonWriter[Unit] = (_: Unit) => "()"
}
