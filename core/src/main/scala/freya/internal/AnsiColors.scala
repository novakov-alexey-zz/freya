package freya.internal

private[freya] object AnsiColors {
  private val AnsiRed = "\u001B[31m"
  private val AnsiGreen = "\u001B[32m"
  private val AnsiYellow = "\u001B[33m"
  private val AnsiReset = "\u001B[0m"
  // if empty, it's true
  val Colors: Boolean = !("false" == System.getenv("COLORS"))

  def re: String =
    if (Colors) AnsiRed
    else ""

  def gr: String =
    if (Colors) AnsiGreen
    else ""

  def ye: String =
    if (Colors) AnsiYellow
    else ""

  def xx: String =
    if (Colors) AnsiReset
    else ""
}
