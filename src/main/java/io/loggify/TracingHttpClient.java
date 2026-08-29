package io.loggify;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/** Outbound {@link HttpClient} that records client spans and injects {@code traceparent}. */
public final class TracingHttpClient extends HttpClient {
  private final HttpClient delegate;

  private TracingHttpClient(HttpClient delegate) {
    this.delegate = delegate;
  }

  public static HttpClient wrap(HttpClient client) {
    if (client instanceof TracingHttpClient) return client;
    return new TracingHttpClient(client);
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return delegate.cookieHandler();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return delegate.connectTimeout();
  }

  @Override
  public Redirect followRedirects() {
    return delegate.followRedirects();
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return delegate.proxy();
  }

  @Override
  public SSLContext sslContext() {
    return delegate.sslContext();
  }

  @Override
  public SSLParameters sslParameters() {
    return delegate.sslParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return delegate.authenticator();
  }

  @Override
  public Version version() {
    return delegate.version();
  }

  @Override
  public Optional<Executor> executor() {
    return delegate.executor();
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
      throws IOException, InterruptedException {
    if (Monitor.isCollectorUrl(request.uri().toString())) {
      return delegate.send(request, responseBodyHandler);
    }
    try {
      return Monitor.withSpan("HTTP " + request.method(), SpanKind.client, span -> {
        span.setAttribute("http.method", request.method());
        span.setAttribute("http.url", clip(request.uri().toString()));
        try {
          HttpResponse<T> response = delegate.send(withTraceparent(request, Monitor.injectTraceparent()), responseBodyHandler);
          span.setAttribute("http.status_code", response.statusCode());
          if (response.statusCode() >= 500) span.setStatus(SpanStatus.error);
          return response;
        } catch (IOException | InterruptedException e) {
          throw new HttpSendException(e);
        }
      });
    } catch (HttpSendException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException io) throw io;
      if (cause instanceof InterruptedException ie) throw ie;
      throw e;
    }
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
    return sendAsync(request, responseBodyHandler, null);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> responseBodyHandler,
      HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
    if (Monitor.isCollectorUrl(request.uri().toString())) {
      return delegate.sendAsync(request, responseBodyHandler, pushPromiseHandler);
    }
    SpanHandle span = Monitor.startSpan(
        "HTTP " + request.method(), SpanKind.client, Map.of(
            "http.method", request.method(),
            "http.url", clip(request.uri().toString())));
    HttpRequest traced = withTraceparent(
        request,
        Monitor.injectTraceparent(new Monitor.TraceContext(span.traceId(), span.spanId())));
    return delegate
        .sendAsync(traced, responseBodyHandler, pushPromiseHandler)
        .whenComplete((response, error) -> {
          if (response != null) span.setAttribute("http.status_code", response.statusCode());
          span.end(error != null || (response != null && response.statusCode() >= 500)
              ? SpanStatus.error
              : SpanStatus.ok);
        });
  }

  @Override
  public WebSocket.Builder newWebSocketBuilder() {
    return delegate.newWebSocketBuilder();
  }

  private static HttpRequest withTraceparent(HttpRequest request, String header) {
    if (header == null) return request;
    return HttpRequest.newBuilder(request, (name, value) -> true)
        .header("traceparent", header)
        .build();
  }

  private static String clip(String value) {
    return value.length() <= 512 ? value : value.substring(0, 512);
  }

  static final class HttpSendException extends RuntimeException {
    private HttpSendException(Exception cause) {
      super(cause);
    }
  }
}
