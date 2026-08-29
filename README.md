# loggify-java

Java monitoring SDK for Loggify. Incoming HTTP is captured with a servlet filter; Spring MVC route templates (`GET /orders/{id}`) come from `LoggifyHandlerInterceptor`. Logs, errors, traces, and runtime metrics are posted as Loggify JSON to ingest.

Call `Monitor.init` **before** starting the web server.

```java
import io.loggify.Monitor;
import io.loggify.MonitorOptions;

Monitor.init(MonitorOptions.builder()
    .apiKey(System.getenv("LOGGIFY_KEY"))
    .service("orders-api")
    .environment("production")
    .endpoint(System.getenv().getOrDefault("LOGGIFY_ENDPOINT", "http://localhost:3001"))
    .build());
```

## Maven

```xml
<dependency>
  <groupId>io.loggify</groupId>
  <artifactId>loggify-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Servlet / Spring Boot

Register `LoggifyServletFilter` on `/*`. Spring Boot 3 apps can set properties instead of calling `init` by hand:

```properties
loggify.api-key=${LOGGIFY_KEY}
loggify.service=orders-api
loggify.environment=production
loggify.endpoint=http://localhost:3001
```

`LoggifyAutoConfiguration` registers the filter and a Spring interceptor so spans use controller patterns:

```text
GET /orders/{id}
 └── HTTP GET   (outbound HttpClient, RestTemplate, or RestClient)
```

```java
@SpringBootApplication
public class App {
  public static void main(String[] args) {
    Monitor.init(MonitorOptions.builder()
        .apiKey(System.getenv("LOGGIFY_KEY"))
        .service("orders-api")
        .environment("production")
        .build());
    SpringApplication.run(App.class, args);
  }
}
```

Without Boot, add the filter yourself:

```java
registrationBean.setFilter(new io.loggify.http.LoggifyServletFilter());
```

## Logs

```java
Monitor.info("order accepted", Map.of("orderId", "ord_123"));
Monitor.warn("queue delayed", Map.of("lagMs", 420));
Monitor.error("payment failed", Map.of("provider", "stripe"));
```

After init, `java.util.logging` is captured too (`captureJul(false)` to disable).

## Errors

```java
try {
  charge(order);
} catch (Exception err) {
  Monitor.captureException(err, "/pay", "POST", 500);
  throw err;
}
```

HTTP 5xx from the servlet filter is captured automatically.

## Traces

Incoming `traceparent` continues a distributed trace. Outbound `HttpClient`
(`Monitor.httpClient()`), and Spring `RestTemplate` / `RestClient` (auto-config),
inject the **client** span as W3C `traceparent`.

```java
Monitor.withSpan("charge", () -> {
  charge(order);
});

HttpClient client = Monitor.httpClient(); // client spans + traceparent
HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());

String header = Monitor.injectTraceparent(); // 00-{traceId}-{spanId}-01
Monitor.TraceContext parent = Monitor.extractTraceparent(incomingHeader);
```

Datastore queries are not auto-patched (no Java agent). Wrap work with `Monitor.withSpan` or send OTLP from JDBC instrumentations to the same ingest URL.
