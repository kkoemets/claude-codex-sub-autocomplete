fun clamp(value: Int, minimum: Int, maximum: Int): Int =
  value.coerceIn(minimum, maximum)
