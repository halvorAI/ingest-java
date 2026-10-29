package com.halvorai.ingest.x402;

import com.halvorai.ingest.AgentContext;
import com.halvorai.ingest.HalvorClient;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Wraps a java.net.http.HttpClient so the x402 payments made through it are recorded. Requests
 * and responses pass through unchanged. The agent context is the calling thread's at the time
 * of the call, also for {@link #sendAsync}.
 *
 * <pre>{@code
 * X402HttpClient http = X402HttpClient.wrap(HttpClient.newHttpClient(), halvor);
 * HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
 * }</pre>
 */
public final class X402HttpClient {
  private final HttpClient http;
  private final X402Observer observer;

  private X402HttpClient(HttpClient http, X402Observer observer) {
    this.http = http;
    this.observer = observer;
  }

  /** Wraps an HTTP client. */
  public static X402HttpClient wrap(HttpClient http, HalvorClient halvor) {
    return new X402HttpClient(http, X402Observer.forClient(halvor));
  }

  /** The wrapped client, for anything else. */
  public HttpClient unwrap() {
    return http;
  }

  /** Sends a request, as {@link HttpClient#send}. */
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    AgentContext ctx = AgentContext.current();
    String url = request.uri().toString();
    Map<String, Object> payload = observer.onRequest(url, name -> request.headers().firstValue(name).orElse(null), ctx);
    HttpResponse<T> res = http.send(request, handler);
    observer.onResponse(url, res.statusCode(), name -> res.headers().firstValue(name).orElse(null), payload, ctx);
    return res;
  }

  /** Sends a request asynchronously, as {@link HttpClient#sendAsync}. */
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    AgentContext ctx = AgentContext.current();
    String url = request.uri().toString();
    Map<String, Object> payload = observer.onRequest(url, name -> request.headers().firstValue(name).orElse(null), ctx);
    return http.sendAsync(request, handler).thenApply(res -> {
      observer.onResponse(url, res.statusCode(), name -> res.headers().firstValue(name).orElse(null), payload, ctx);
      return res;
    });
  }
}
