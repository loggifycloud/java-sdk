package io.loggify.spring;

import io.loggify.Monitor;
import io.loggify.SpanHandle;
import io.loggify.SpanKind;
import io.loggify.SpanStatus;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Outbound Spring HTTP interceptor: client span + W3C {@code traceparent}. */
public final class LoggifyClientInterceptor implements ClientHttpRequestInterceptor {
  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    String url = request.getURI().toString();
    if (Monitor.isCollectorUrl(url)) return execution.execute(request, body);

    String method = request.getMethod() == null ? "GET" : request.getMethod().name();
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("http.method", method);
    attributes.put("http.url", clip(url));
    SpanHandle span = Monitor.startSpan("HTTP " + method, SpanKind.client, attributes);
    String header =
        Monitor.injectTraceparent(new Monitor.TraceContext(span.traceId(), span.spanId()));
    if (header != null) request.getHeaders().set("traceparent", header);
    try {
      ClientHttpResponse response = execution.execute(request, body);
      span.setAttribute("http.status_code", response.getStatusCode().value());
      span.end(response.getStatusCode().is5xxServerError() ? SpanStatus.error : SpanStatus.ok);
      return response;
    } catch (IOException | RuntimeException | Error error) {
      span.end(SpanStatus.error);
      throw error;
    }
  }

  private static String clip(String value) {
    return value.length() <= 512 ? value : value.substring(0, 512);
  }
}
