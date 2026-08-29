package io.loggify.spring;

import io.loggify.Monitor;
import io.loggify.MonitorOptions;
import io.loggify.http.LoggifyServletFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@EnableConfigurationProperties(LoggifyProperties.class)
public class LoggifyAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  FilterRegistrationBean<LoggifyServletFilter> loggifyServletFilter(LoggifyProperties properties) {
    maybeInit(properties);
    FilterRegistrationBean<LoggifyServletFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new LoggifyServletFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.addUrlPatterns("/*");
    return registration;
  }

  @Bean
  WebMvcConfigurer loggifyWebMvcConfigurer() {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoggifyHandlerInterceptor());
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean
  LoggifyClientInterceptor loggifyClientInterceptor() {
    return new LoggifyClientInterceptor();
  }

  @Bean
  @ConditionalOnClass(RestTemplateCustomizer.class)
  RestTemplateCustomizer loggifyRestTemplateCustomizer(LoggifyClientInterceptor interceptor) {
    return template -> {
      if (!template.getInterceptors().contains(interceptor)) {
        template.getInterceptors().add(interceptor);
      }
    };
  }

  @Bean
  @ConditionalOnClass(RestClientCustomizer.class)
  RestClientCustomizer loggifyRestClientCustomizer(LoggifyClientInterceptor interceptor) {
    return builder -> builder.requestInterceptor(interceptor);
  }

  private static void maybeInit(LoggifyProperties properties) {
    if (properties.getApiKey() == null || properties.getApiKey().isBlank()) return;
    if (properties.getService() == null || properties.getService().isBlank()) return;
    Monitor.init(
        MonitorOptions.builder()
            .apiKey(properties.getApiKey())
            .service(properties.getService())
            .environment(
                properties.getEnvironment() == null || properties.getEnvironment().isBlank()
                    ? "production"
                    : properties.getEnvironment())
            .endpoint(properties.getEndpoint())
            .build());
  }
}
