package io.loggify.http;

import io.loggify.Monitor;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Records incoming HTTP as a server span. Register before the application handles the request.
 * Spring MVC users also get route templates from {@code LoggifyHandlerInterceptor}.
 */
public final class LoggifyServletFilter implements Filter {
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest httpRequest)
        || !(response instanceof HttpServletResponse httpResponse)) {
      chain.doFilter(request, response);
      return;
    }
    String path = path(httpRequest);
    Monitor.RequestScope scope =
        Monitor.beginRequest(httpRequest.getMethod(), path, httpRequest.getHeader("traceparent"));
    try {
      chain.doFilter(request, response);
    } catch (IOException | ServletException | RuntimeException | Error error) {
      Monitor.captureException(error, path, httpRequest.getMethod(), 500);
      throw error;
    } finally {
      scope.setStatus(httpResponse.getStatus());
      scope.setRequestSize(contentLength(httpRequest.getHeader("content-length")));
      scope.setResponseSize(contentLength(httpResponse.getHeader("content-length")));
      scope.close();
    }
  }

  private static String path(HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri == null || uri.isEmpty()) return "/";
    int query = uri.indexOf('?');
    return query >= 0 ? uri.substring(0, query) : uri;
  }

  private static Integer contentLength(String value) {
    if (value == null || value.isEmpty()) return null;
    try {
      int length = Integer.parseInt(value);
      return length >= 0 ? length : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }
}
