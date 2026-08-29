package io.loggify;

public interface SpanHandle {
  String traceId();

  String spanId();

  String parentSpanId();

  SpanHandle setName(String name);

  SpanHandle setAttribute(String key, Object value);

  SpanHandle setStatus(SpanStatus status);

  void end();

  void end(SpanStatus status);
}
