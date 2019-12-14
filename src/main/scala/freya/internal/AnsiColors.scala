package freya.internal

private[freya] object AnsiColors {
  private val ANSI_R = "\u001B[31m"
  private val ANSI_G = "\u001B[32m"
  private val ANSI_Y = "\u001B[33m"
  private val ANSI_RESET = "\u001B[0m"
  // if empty, it's true
  val COLORS: Boolean = !("false" == System.getenv("COLORS"))

  def re: String = if (COLORS) ANSI_R
  else ""

  def gr: String = if (COLORS) ANSI_G
  else ""

  def ye: String = if (COLORS) ANSI_Y
  else ""

  def xx: String = if (COLORS) ANSI_RESET
  else ""
}
