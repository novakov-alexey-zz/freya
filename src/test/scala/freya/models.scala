package freya

final case class Principal(name: String, password: String, value: String = "")
final case class Kerb(realm: String, principals: List[Principal], failInTest: Boolean)
final case class KerbStatus(ready: Boolean = false)