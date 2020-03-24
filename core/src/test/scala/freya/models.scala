package freya

final case class Principal(name: String, password: String, value: String = "")
final case class Kerb(realm: String, principals: List[Principal], failInTest: Boolean, index: Int = 0)
final case class Status(ready: Boolean = false)