package io.loggify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MonitorTest {
  private HttpServer server;
  private final List<Post> posts = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startCollector() throws Exception {
    posts.clear();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/", exchange -> {
      byte[] bytes = exchange.getRequestBody().readAllBytes();
      posts.add(new Post(exchange.getRequestURI().getPath(), new String(bytes, StandardCharsets.UTF_8)));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
    });
    server.start();
    int port = server.getAddress().getPort();
    Monitor.init(
        MonitorOptions.builder()
            .apiKey("test-key")
            .service("orders-api")
            .environment("test")
            .endpoint("http://127.0.0.1:" + port)
            .flushIntervalMs(60_000)
            .captureJul(false)
            .build());
  }

  @AfterEach
  void stopCollector() {
    server.stop(0);
  }

  @Test
  void recordsLogsAndExplicitSpans() throws Exception {
    Monitor.info("order accepted", Map.of("orderId", "ord_123"));
    Monitor.warn("queue delayed", Map.of("lagMs", 420));
    waitUntil(() -> posts.stream().anyMatch(post -> post.path.equals("/v1/logs") && post.body.contains("order accepted")));

    Monitor.withSpan("charge", SpanKind.client, span -> {
      span.setAttribute("payment.provider", "test");
      assertEquals(span.traceId(), Monitor.currentTraceContext().traceId());
      return null;
    });
    Monitor.flush();
    waitUntil(() -> posts.stream().anyMatch(post -> post.path.equals("/v1/ingest") && post.body.contains("charge")));

    String ingest = posts.stream().filter(post -> post.path.equals("/v1/ingest")).map(post -> post.body).reduce("", String::concat);
    assertTrue(ingest.contains("\"name\":\"charge\""));
    assertTrue(ingest.contains("\"kind\":\"client\""));
    assertTrue(ingest.contains("payment.provider"));
  }

  @Test
  void recordsIncomingHttpRouteTemplates() throws Exception {
    try (Monitor.RequestScope scope = Monitor.beginRequest("GET", "/orders/42", null)) {
      Monitor.setHttpRoute("/orders/{id}");
      Monitor.setSpanName("GET /orders/{id}");
      Monitor.setSpanAttribute("http.route", "/orders/{id}");
      scope.setStatus(200);
    }
    Monitor.flush();
    waitUntil(() -> posts.stream().anyMatch(post -> post.path.equals("/v1/ingest") && post.body.contains("/orders/{id}")));

    String ingest = posts.stream().filter(post -> post.path.equals("/v1/ingest")).map(post -> post.body).reduce("", String::concat);
    assertTrue(ingest.contains("\"route\":\"/orders/{id}\""));
    assertTrue(ingest.contains("\"name\":\"GET /orders/{id}\""));
    assertTrue(ingest.contains("\"kind\":\"server\""));
    assertFalse(ingest.contains("\"route\":\"/orders/42\""));
  }

  @Test
  void capturesExceptions() throws Exception {
    Monitor.captureException(new IllegalStateException("payment failed"), "/pay", "POST", 500);
    Monitor.flush();
    waitUntil(() -> posts.stream().anyMatch(post -> post.path.equals("/v1/ingest") && post.body.contains("payment failed")));

    String ingest = posts.stream().filter(post -> post.path.equals("/v1/ingest")).map(post -> post.body).reduce("", String::concat);
    assertTrue(ingest.contains("\"exceptionType\":\"IllegalStateException\""));
    assertTrue(ingest.contains("\"endpoint\":\"/pay\""));
  }

  @Test
  void continuesW3cTraceparentAcrossHttpHops() throws Exception {
    assertEquals(
        new Monitor.TraceContext("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb"),
        Monitor.extractTraceparent("00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01"));
    assertEquals(null, Monitor.extractTraceparent("nope"));

    List<String> captured = new CopyOnWriteArrayList<>();
    HttpServer echo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    echo.createContext("/", exchange -> {
      captured.add(exchange.getRequestHeaders().getFirst("traceparent"));
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
    });
    echo.start();
    try {
      String parentTraceId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
      String parentSpanId = "bbbbbbbbbbbbbbbb";
      HttpClient client = Monitor.httpClient();
      HttpRequest request = HttpRequest.newBuilder(
              URI.create("http://127.0.0.1:" + echo.getAddress().getPort() + "/pay"))
          .GET()
          .build();
      try (Monitor.RequestScope scope =
          Monitor.beginRequest("GET", "/orders/1", "00-" + parentTraceId + "-" + parentSpanId + "-01")) {
        assertEquals(
            "00-" + parentTraceId + "-" + parentSpanId + "-01",
            Monitor.injectTraceparent(new Monitor.TraceContext(parentTraceId, parentSpanId)));
        assertTrue(Monitor.injectTraceparent().startsWith("00-" + parentTraceId + "-"));
        client.send(request, HttpResponse.BodyHandlers.discarding());
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).join();
        scope.setStatus(200);
      }
      Monitor.flush();
      waitUntil(() ->
          posts.stream().anyMatch(post -> post.path.equals("/v1/ingest") && post.body.contains("\"kind\":\"client\"")));

      String ingest =
          posts.stream().filter(post -> post.path.equals("/v1/ingest")).map(post -> post.body).reduce("", String::concat);
      assertTrue(ingest.contains("\"traceId\":\"" + parentTraceId + "\""));
      assertTrue(ingest.contains("\"name\":\"GET /orders/1\""));
      assertTrue(ingest.contains("\"kind\":\"server\""));
      assertTrue(ingest.contains("\"kind\":\"client\""));
      Matcher server =
          Pattern.compile("\"spanId\":\"([0-9a-f]{16})\",\"parentSpanId\":\"" + parentSpanId + "\"").matcher(ingest);
      assertTrue(server.find());
      String serverSpanId = server.group(1);
      assertEquals(2, captured.size());
      for (String header : captured) {
        assertEquals("00-" + parentTraceId + "-", header.substring(0, 36));
        assertTrue(header.endsWith("-01"));
        String clientSpanId = header.substring(36, 52);
        assertTrue(ingest.contains("\"spanId\":\"" + clientSpanId + "\""));
        assertTrue(ingest.contains("\"parentSpanId\":\"" + serverSpanId + "\""));
      }
    } finally {
      echo.stop(0);
    }
  }

  private static void waitUntil(Check check) throws InterruptedException {
    long deadline = System.nanoTime() + 2_000_000_000L;
    while (!check.ok()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out waiting for collector posts");
      }
      Thread.sleep(20);
    }
  }

  @FunctionalInterface
  private interface Check {
    boolean ok();
  }

  private record Post(String path, String body) {}
}
