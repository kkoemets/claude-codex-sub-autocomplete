final class Sample {
  static int clamp(int value, int minimum, int maximum) {
    return Math.min(maximum, Math.max(minimum, value));
  }
}
