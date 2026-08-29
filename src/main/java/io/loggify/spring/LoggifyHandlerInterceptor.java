package io.loggify.spring;

import io.loggify.Monitor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Rewrites the active server span to the Spring route template, e.g. {@code GET /orders/{id}}.
 */
public final class LoggifyHandlerInterceptor implements HandlerInterceptor {
  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    try {
      Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      if (pattern instanceof String route && !route.isEmpty()) {
        String method = request.getMethod() == null ? "GET" : request.getMethod().toUpperCase();
        Monitor.setHttpRoute(route);
        Monitor.setSpanName(method + " " + route);
        Monitor.setSpanAttribute("http.route", route);
      }
    } catch (Throwable ignored) {
      /* never throw into host app */
    }
    return true;
  }
}
