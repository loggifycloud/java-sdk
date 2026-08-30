package io.loggify;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

public final class MonitorOptions {
  public final String apiKey;
  public final String service;
  public final String environment;
  public final String endpoint;
  public final double sampleRate;
  public final long flushIntervalMs;
  public final int maxBuffer;
  public final long timeoutMs;
  public final boolean captureJul;
  public final String hostname;
  final HttpClient httpClient;

  private MonitorOptions(Builder builder) {
    this.apiKey = Objects.requireNonNull(builder.apiKey, "apiKey");
    this.service = Objects.requireNonNull(builder.service, "service");
    this.environment = Objects.requireNonNull(builder.environment, "environment");
    this.endpoint = builder.endpoint == null ? "https://ingest.loggify.cloud" : builder.endpoint;
    this.sampleRate = builder.sampleRate;
    this.flushIntervalMs = builder.flushIntervalMs;
    this.maxBuffer = builder.maxBuffer;
    this.timeoutMs = builder.timeoutMs;
    this.captureJul = builder.captureJul;
    this.hostname = builder.hostname;
    this.httpClient = builder.httpClient;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String apiKey;
    private String service;
    private String environment;
    private String endpoint;
    private double sampleRate = 1;
    private long flushIntervalMs = 2000;
    private int maxBuffer = 500;
    private long timeoutMs = 1500;
    private boolean captureJul = true;
    private String hostname;
    private HttpClient httpClient;

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public Builder service(String service) {
      this.service = service;
      return this;
    }

    public Builder environment(String environment) {
      this.environment = environment;
      return this;
    }

    public Builder endpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    public Builder sampleRate(double sampleRate) {
      this.sampleRate = sampleRate;
      return this;
    }

    public Builder flushIntervalMs(long flushIntervalMs) {
      this.flushIntervalMs = flushIntervalMs;
      return this;
    }

    public Builder maxBuffer(int maxBuffer) {
      this.maxBuffer = maxBuffer;
      return this;
    }

    public Builder timeoutMs(long timeoutMs) {
      this.timeoutMs = timeoutMs;
      return this;
    }

    /** Capture java.util.logging as Loggify logs. Default true. */
    public Builder captureJul(boolean captureJul) {
      this.captureJul = captureJul;
      return this;
    }

    /** Hostname attached to runtime metrics. Defaults to HOSTNAME or the local host name. */
    public Builder hostname(String hostname) {
      this.hostname = hostname;
      return this;
    }

    Builder httpClient(HttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    public MonitorOptions build() {
      return new MonitorOptions(this);
    }
  }

  HttpClient httpClientOrDefault() {
    if (httpClient != null) return httpClient;
    return HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
  }
}
