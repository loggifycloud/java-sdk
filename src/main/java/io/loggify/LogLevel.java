package io.loggify;

public enum LogLevel {
  DEBUG,
  INFO,
  WARN,
  ERROR,
  FATAL;

  public static LogLevel from(String value) {
    if (value == null) return INFO;
    try {
      return LogLevel.valueOf(value.toUpperCase());
    } catch (IllegalArgumentException ignored) {
      return INFO;
    }
  }
}
