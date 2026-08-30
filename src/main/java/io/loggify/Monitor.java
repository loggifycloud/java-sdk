package io.loggify;

import io.loggify.internal.Json;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Monitor {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Pattern TRACEPARENT =
      Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-0[01]$", Pattern.CASE_INSENSITIVE);
  private static final ThreadLocal<ActiveSpan> CONTEXT = new ThreadLocal<>();

  private static volatile MonitorOptions opts;
  private static volatile HttpClient http;
  private static final Buffer<Map<String, Object>> httpBuf = new Buffer<>();
  private static final Buffer<Map<String, Object>> errorBuf = new Buffer<>();
  private static final Buffer<Map<String, Object>> metricBuf = new Buffer<>();
  private static final Buffer<Map<String, Object>> spanBuf = new Buffer<>();
  private static ScheduledExecutorService scheduler;
  private static volatile boolean julInstrumented;
  private static volatile boolean errorsInstrumented;
  private static volatile boolean capturingJul;

  private Monitor() {}

  public static void init(MonitorOptions options) {
    opts = options;
    http = options.httpClientOrDefault();
    httpBuf.max = options.maxBuffer;
    errorBuf.max = options.maxBuffer;
    metricBuf.max = options.maxBuffer;
    spanBuf.max = options.maxBuffer;
    instrumentErrors();
    instrumentJul();
    if (scheduler != null) scheduler.shutdownNow();
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "loggify-flush");
      thread.setDaemon(true);
      return thread;
    });
    scheduler.scheduleAtFixedRate(Monitor::flushSafe, options.flushIntervalMs, options.flushIntervalMs, TimeUnit.MILLISECONDS);
    scheduler.scheduleAtFixedRate(Monitor::collectRuntime, 15, 15, TimeUnit.SECONDS);
    collectRuntime();
  }

  public static void captureException(Throwable err) {
    captureException(err, null, null, null);
  }

  public static void captureException(Throwable err, String endpoint, String method, Integer statusCode) {
    try {
      Throwable error = err == null ? new RuntimeException("unknown") : err;
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("message", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
      payload.put("exceptionType", error.getClass().getSimpleName());
      payload.put("stackTrace", stack(error));
      ActiveSpan active = CONTEXT.get();
      if (active != null) payload.put("traceId", active.traceId);
      if (endpoint != null) payload.put("endpoint", endpoint);
      if (method != null) payload.put("method", method);
      if (statusCode != null) payload.put("statusCode", statusCode);
      errorBuf.push(payload);

      Map<String, Object> logAttributes = new LinkedHashMap<>();
      logAttributes.put("exceptionType", error.getClass().getSimpleName());
      logAttributes.put("stackTrace", stack(error));
      if (endpoint != null) logAttributes.put("endpoint", endpoint);
      if (method != null) logAttributes.put("method", method);
      if (statusCode != null) logAttributes.put("statusCode", statusCode);
      log(LogLevel.ERROR, error.getClass().getSimpleName() + ": " + payload.get("message"), logAttributes);
    } catch (Throwable ignored) {
      /* never throw into host app */
    }
  }

  public static SpanHandle startSpan(String name) {
    return startSpan(name, SpanKind.internal, Map.of(), null);
  }

  public static SpanHandle startSpan(String name, SpanKind kind, Map<String, Object> attributes) {
    return startSpan(name, kind, attributes, null);
  }

  public static SpanHandle startSpan(
      String name, SpanKind kind, Map<String, Object> attributes, TraceContext parent) {
    ActiveSpan active = CONTEXT.get();
    String traceId;
    String parentSpanId;
    if (parent != null) {
      traceId = parent.traceId();
      parentSpanId = parent.spanId();
    } else if (active != null) {
      traceId = active.traceId;
      parentSpanId = active.spanId;
    } else {
      traceId = hex(16);
      parentSpanId = null;
    }
    String spanId = hex(8);
    String startedAt = Instant.now().toString();
    long started = System.nanoTime();
    Map<String, Object> attrs = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
    return new SpanImpl(name, kind, traceId, spanId, parentSpanId, startedAt, started, attrs);
  }

  public static <T> T withSpan(String name, Supplier<T> operation) {
    return withSpan(name, SpanKind.internal, span -> operation.get());
  }

  public static <T> T withSpan(String name, SpanKind kind, Supplier<T> operation) {
    return withSpan(name, kind, span -> operation.get());
  }

  public static <T> T withSpan(String name, SpanKind kind, java.util.function.Function<SpanHandle, T> operation) {
    SpanHandle span = startSpan(name, kind, Map.of());
    ActiveSpan previous = CONTEXT.get();
    CONTEXT.set(new ActiveSpan(span.traceId(), span.spanId(), span, previous == null ? null : previous.httpRoute));
    try {
      T result = operation.apply(span);
      span.end();
      return result;
    } catch (RuntimeException | Error e) {
      span.end(SpanStatus.error);
      throw e;
    } finally {
      CONTEXT.set(previous);
    }
  }

  public static void withSpan(String name, Runnable operation) {
    withSpan(name, () -> {
      operation.run();
      return null;
    });
  }

  public static TraceContext currentTraceContext() {
    ActiveSpan active = CONTEXT.get();
    return active == null ? null : new TraceContext(active.traceId, active.spanId);
  }

  public static void setHttpRoute(String route) {
    try {
      ActiveSpan active = CONTEXT.get();
      if (active == null) return;
      active.httpRoute = clip(route, 512);
    } catch (Throwable ignored) {
      /* never throw into host app */
    }
  }

  public static void setSpanName(String name) {
    try {
      ActiveSpan active = CONTEXT.get();
      if (active != null && active.span != null) active.span.setName(name);
    } catch (Throwable ignored) {
      /* never throw into host app */
    }
  }

  public static void setSpanAttribute(String key, Object value) {
    try {
      ActiveSpan active = CONTEXT.get();
      if (active != null && active.span != null) active.span.setAttribute(key, value);
    } catch (Throwable ignored) {
      /* never throw into host app */
    }
  }

  public static String traceparent() {
    return injectTraceparent();
  }

  public static String injectTraceparent() {
    return injectTraceparent(currentTraceContext());
  }

  public static String injectTraceparent(TraceContext context) {
    if (context == null || context.traceId() == null || context.spanId() == null) return null;
    return "00-" + context.traceId() + "-" + context.spanId() + "-01";
  }

  public static TraceContext extractTraceparent(String header) {
    return parseTraceparent(header);
  }

  public static RequestScope beginRequest(String method, String path, String traceparent) {
    TraceContext parent = extractTraceparent(traceparent);
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("http.method", method);
    attributes.put("http.route", path);
    SpanHandle span = startSpan(method + " " + path, SpanKind.server, attributes, parent);
    ActiveSpan previous = CONTEXT.get();
    CONTEXT.set(new ActiveSpan(span.traceId(), span.spanId(), span, path));
    return new RequestScope(span, method, path, System.nanoTime(), previous);
  }

  public static void log(String message) {
    log(LogLevel.INFO, message, null);
  }

  public static void log(String message, Map<String, Object> attributes) {
    log(LogLevel.INFO, message, attributes);
  }

  public static void log(LogLevel level, String message, Map<String, Object> attributes) {
    try {
      if (opts == null) return;
      ActiveSpan active = CONTEXT.get();
      Map<String, Object> attrs = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
      if (active != null) {
        attrs.put("traceId", active.traceId);
        attrs.put("spanId", active.spanId);
      }
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("level", level.name());
      event.put("message", message);
      event.put("attributes", attrs);
      event.put("serviceName", opts.service);
      event.put("environment", opts.environment);
      event.put("timestamp", Instant.now().toString());
      post("/v1/logs", Json.object(Map.of("logs", List.of(event))), 0);
    } catch (Throwable ignored) {
      /* ignore */
    }
  }

  public static void debug(String message) {
    debug(message, null);
  }

  public static void debug(String message, Map<String, Object> attributes) {
    log(LogLevel.DEBUG, message, attributes);
  }

  public static void info(String message) {
    info(message, null);
  }

  public static void info(String message, Map<String, Object> attributes) {
    log(LogLevel.INFO, message, attributes);
  }

  public static void warn(String message) {
    warn(message, null);
  }

  public static void warn(String message, Map<String, Object> attributes) {
    log(LogLevel.WARN, message, attributes);
  }

  public static void error(String message) {
    error(message, null);
  }

  public static void error(String message, Map<String, Object> attributes) {
    log(LogLevel.ERROR, message, attributes);
  }

  public static void fatal(String message) {
    fatal(message, null);
  }

  public static void fatal(String message, Map<String, Object> attributes) {
    log(LogLevel.FATAL, message, attributes);
  }

  public static void flush() {
    if (opts == null) return;
    List<Map<String, Object>> httpRequests = httpBuf.drain();
    List<Map<String, Object>> errors = errorBuf.drain();
    List<Map<String, Object>> metrics = metricBuf.drain();
    List<Map<String, Object>> spans = spanBuf.drain();
    if (httpRequests.isEmpty() && errors.isEmpty() && metrics.isEmpty() && spans.isEmpty()) return;

    Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
    for (Map<String, Object> span : spans) {
      String traceId = String.valueOf(span.get("traceId"));
      grouped.computeIfAbsent(traceId, key -> new ArrayList<>()).add(span);
    }
    List<Map<String, Object>> traces = new ArrayList<>();
    for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
      List<Map<String, Object>> cleaned = new ArrayList<>();
      for (Map<String, Object> span : entry.getValue()) {
        Map<String, Object> copy = new LinkedHashMap<>(span);
        copy.remove("traceId");
        cleaned.add(copy);
      }
      Map<String, Object> trace = new LinkedHashMap<>();
      trace.put("traceId", entry.getKey());
      trace.put("serviceName", opts.service);
      trace.put("environment", opts.environment);
      trace.put("spans", cleaned);
      traces.add(trace);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("httpRequests", httpRequests);
    body.put("errors", errors);
    body.put("metrics", metrics);
    body.put("traces", traces);
    post("/v1/ingest", Json.object(body), 0);
  }

  public static HttpClient httpClient() {
    return TracingHttpClient.wrap(HttpClient.newHttpClient());
  }

  public static HttpClient wrap(HttpClient client) {
    return TracingHttpClient.wrap(client);
  }

  public static boolean isCollectorUrl(String url) {
    return opts != null && url != null && url.startsWith(opts.endpoint);
  }

  private static void flushSafe() {
    try {
      flush();
    } catch (Throwable ignored) {
      /* ignore */
    }
  }

  private static void post(String path, String body, int attempt) {
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(opts.endpoint + path))
          .timeout(Duration.ofMillis(opts.timeoutMs))
          .header("content-type", "application/json")
          .header("x-api-key", opts.apiKey)
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();
      http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
          .whenComplete((response, error) -> {
            boolean retry = error != null || (response != null && response.statusCode() == 429);
            if (retry && attempt < 3 && scheduler != null) {
              long delay = 200L * (1L << attempt);
              scheduler.schedule(() -> post(path, body, attempt + 1), delay, TimeUnit.MILLISECONDS);
            }
          });
    } catch (Throwable ignored) {
      /* never throw into host app */
    }
  }

  private static void instrumentErrors() {
    if (errorsInstrumented) return;
    errorsInstrumented = true;
    Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
      captureException(error);
      if (previous != null) previous.uncaughtException(thread, error);
      else error.printStackTrace();
    });
  }

  private static void instrumentJul() {
    if (julInstrumented) return;
    julInstrumented = true;
    Logger.getLogger("").addHandler(new Handler() {
      @Override
      public void publish(LogRecord record) {
        if (capturingJul || opts == null || !opts.captureJul) return;
        capturingJul = true;
        try {
          LogLevel level = julLevel(record.getLevel());
          Map<String, Object> attributes = new LinkedHashMap<>();
          attributes.put("source", "jul");
          if (record.getLoggerName() != null) attributes.put("logger", record.getLoggerName());
          log(level, record.getMessage() == null ? "" : record.getMessage(), attributes);
        } catch (Throwable ignored) {
          /* never throw into host app */
        } finally {
          capturingJul = false;
        }
      }

      @Override
      public void flush() {}

      @Override
      public void close() {}
    });
  }

  private static LogLevel julLevel(java.util.logging.Level level) {
    if (level.intValue() >= java.util.logging.Level.SEVERE.intValue()) return LogLevel.ERROR;
    if (level.intValue() >= java.util.logging.Level.WARNING.intValue()) return LogLevel.WARN;
    if (level.intValue() >= java.util.logging.Level.INFO.intValue()) return LogLevel.INFO;
    return LogLevel.DEBUG;
  }

  private static void collectRuntime() {
    try {
      MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
      OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
      RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
      metricBuf.push(metric("memory_usage", bytesToMb(memory.getHeapMemoryUsage().getUsed() + memory.getNonHeapMemoryUsage().getUsed())));
      metricBuf.push(metric("heap_used", bytesToMb(memory.getHeapMemoryUsage().getUsed())));
      metricBuf.push(metric("cpu_usage", os.getSystemLoadAverage()));
      metricBuf.push(metric("process_uptime", runtime.getUptime() / 1000.0));
    } catch (Throwable ignored) {
      /* ignore */
    }
  }

  private static Map<String, Object> metric(String name, double value) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("metricName", name);
    event.put("value", value);
    if (opts != null) {
      event.put("serviceName", opts.service);
      event.put("environment", opts.environment);
    }
    Map<String, String> tags = new LinkedHashMap<>();
    String host = resolveHostname();
    if (!host.isEmpty()) tags.put("hostname", host);
    tags.put("pid", String.valueOf(ProcessHandle.current().pid()));
    event.put("tags", tags);
    return event;
  }

  private static String resolveHostname() {
    if (opts != null && opts.hostname != null && !opts.hostname.isBlank()) {
      return opts.hostname.trim();
    }
    String env = System.getenv("HOSTNAME");
    if (env != null && !env.isBlank()) return env.trim();
    try {
      String host = InetAddress.getLocalHost().getHostName();
      return host == null ? "" : host.trim();
    } catch (Exception ignored) {
      return "";
    }
  }

  private static double bytesToMb(long bytes) {
    return bytes / 1024.0 / 1024.0;
  }

  private static TraceContext parseTraceparent(String header) {
    if (header == null) return null;
    Matcher match = TRACEPARENT.matcher(header.trim());
    if (!match.matches()) return null;
    return new TraceContext(match.group(1).toLowerCase(), match.group(2).toLowerCase());
  }

  private static String hex(int bytes) {
    byte[] data = new byte[bytes];
    RANDOM.nextBytes(data);
    StringBuilder out = new StringBuilder(bytes * 2);
    for (byte value : data) out.append(String.format("%02x", value));
    return out.toString();
  }

  private static String stack(Throwable error) {
    StringWriter writer = new StringWriter();
    error.printStackTrace(new PrintWriter(writer));
    return writer.toString();
  }

  private static String clip(String value, int max) {
    if (value == null) return "";
    return value.length() <= max ? value : value.substring(0, max);
  }

  public record TraceContext(String traceId, String spanId) {}

  public static final class RequestScope implements AutoCloseable {
    private final SpanHandle span;
    private final String method;
    private final String fallbackPath;
    private final long started;
    private final ActiveSpan previous;
    private int statusCode = 200;
    private Integer requestSize;
    private Integer responseSize;
    private boolean closed;

    private RequestScope(SpanHandle span, String method, String fallbackPath, long started, ActiveSpan previous) {
      this.span = span;
      this.method = method;
      this.fallbackPath = fallbackPath;
      this.started = started;
      this.previous = previous;
    }

    public String traceId() {
      return span.traceId();
    }

    public void setStatus(int statusCode) {
      this.statusCode = statusCode;
    }

    public void setRequestSize(Integer requestSize) {
      this.requestSize = requestSize;
    }

    public void setResponseSize(Integer responseSize) {
      this.responseSize = responseSize;
    }

    @Override
    public void close() {
      if (closed) return;
      closed = true;
      try {
        ActiveSpan active = CONTEXT.get();
        String path = active != null && active.httpRoute != null ? active.httpRoute : fallbackPath;
        span.setAttribute("http.status_code", statusCode);
        span.setAttribute("http.route", path);
        span.end(statusCode >= 500 ? SpanStatus.error : SpanStatus.ok);
        if (opts != null && Math.random() <= opts.sampleRate) {
          Map<String, Object> event = new LinkedHashMap<>();
          event.put("method", method);
          event.put("route", path);
          event.put("statusCode", statusCode);
          event.put("durationMs", (System.nanoTime() - started) / 1_000_000.0);
          if (requestSize != null) event.put("requestSize", requestSize);
          if (responseSize != null) event.put("responseSize", responseSize);
          event.put("serviceName", opts.service);
          event.put("environment", opts.environment);
          event.put("timestamp", Instant.now().toString());
          event.put("traceId", span.traceId());
          httpBuf.push(event);
        }
      } catch (Throwable ignored) {
        span.end(SpanStatus.ok);
      } finally {
        CONTEXT.set(previous);
      }
    }
  }

  private static final class SpanImpl implements SpanHandle {
    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final SpanKind kind;
    private final String startedAt;
    private final long started;
    private final Map<String, Object> attributes;
    private String name;
    private SpanStatus status = SpanStatus.unset;
    private boolean ended;

    private SpanImpl(
        String name,
        SpanKind kind,
        String traceId,
        String spanId,
        String parentSpanId,
        String startedAt,
        long started,
        Map<String, Object> attributes) {
      this.name = clip(name, 512);
      this.kind = kind == null ? SpanKind.internal : kind;
      this.traceId = traceId;
      this.spanId = spanId;
      this.parentSpanId = parentSpanId;
      this.startedAt = startedAt;
      this.started = started;
      this.attributes = attributes;
    }

    @Override
    public String traceId() {
      return traceId;
    }

    @Override
    public String spanId() {
      return spanId;
    }

    @Override
    public String parentSpanId() {
      return parentSpanId;
    }

    @Override
    public SpanHandle setName(String name) {
      if (!ended) this.name = clip(name, 512);
      return this;
    }

    @Override
    public SpanHandle setAttribute(String key, Object value) {
      if (!ended) attributes.put(key, value);
      return this;
    }

    @Override
    public SpanHandle setStatus(SpanStatus status) {
      this.status = status;
      return this;
    }

    @Override
    public void end() {
      end(null);
    }

    @Override
    public void end(SpanStatus finalStatus) {
      if (ended) return;
      ended = true;
      if (opts == null || Math.random() > opts.sampleRate) return;
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("traceId", traceId);
      event.put("spanId", spanId);
      if (parentSpanId != null) event.put("parentSpanId", parentSpanId);
      event.put("name", name);
      event.put("kind", kind.name());
      event.put("status", (finalStatus == null ? status : finalStatus).name());
      event.put("timestamp", startedAt);
      event.put("durationMs", (System.nanoTime() - started) / 1_000_000.0);
      event.put("attributes", attributes);
      event.put("serviceName", opts.service);
      event.put("environment", opts.environment);
      spanBuf.push(event);
    }
  }

  private static final class ActiveSpan {
    final String traceId;
    final String spanId;
    final SpanHandle span;
    String httpRoute;

    private ActiveSpan(String traceId, String spanId, SpanHandle span, String httpRoute) {
      this.traceId = traceId;
      this.spanId = spanId;
      this.span = span;
      this.httpRoute = httpRoute;
    }
  }

  private static final class Buffer<T> {
    private final ArrayDeque<T> items = new ArrayDeque<>();
    int max = 500;

    synchronized void push(T item) {
      if (items.size() >= max) items.removeFirst();
      items.addLast(item);
    }

    synchronized List<T> drain() {
      List<T> out = new ArrayList<>(items);
      items.clear();
      return out;
    }
  }
}
