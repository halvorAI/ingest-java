package com.halvorai.ingest.x402;

import com.halvorai.ingest.AgentContext;
import com.halvorai.ingest.HalvorClient;
import com.halvorai.ingest.HalvorError;
import com.halvorai.ingest.Payee;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Records x402 payments from any HTTP stack: call {@link #onRequest} with the request headers
 * and {@link #onResponse} with the response. Headers are only read; nothing in the payment path
 * changes, and no error ever reaches the caller.
 *
 * <p>On the client side ({@link #forClient}), the observer remembers each URL's 402 challenge
 * and links the payment that answers it with related.previous_event_id. On the server side
 * ({@link #forServer}) events are not linked, because a 402 and the paid retry are separate
 * requests that may come from different clients.
 *
 * <p>For java.net.http, use {@link X402HttpClient}. For a server framework, call the observer
 * from a filter or interceptor that sees the request headers before the handler and the
 * response headers after the x402 payment middleware has set them.
 */
public final class X402Observer {
  private static final int MAX_PENDING_CHALLENGES = 1000;

  private final HalvorClient client;
  private final String adapter;
  private final Map<String, Object> payee;
  private final Map<String, String> requested = new LinkedHashMap<>(16, 0.75f, false) {
    private static final long serialVersionUID = 1L;

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
      return size() > MAX_PENDING_CHALLENGES;
    }
  };

  private X402Observer(HalvorClient client, String adapter, Map<String, Object> payee) {
    this.client = client;
    this.adapter = adapter;
    this.payee = payee;
  }

  /** An observer for an agent that pays for resources. */
  public static X402Observer forClient(HalvorClient client) {
    return new X402Observer(client, X402Mapper.ADAPTER_CLIENT, null);
  }

  /**
   * An observer for a seller. payee is your identity: its type defaults to api_provider and,
   * without a name, id or wallet, its name to "x402 resource server".
   */
  public static X402Observer forServer(HalvorClient client, Payee payee) {
    Map<String, Object> p = payee == null ? new LinkedHashMap<>() : payee.toMap();
    p.putIfAbsent("type", "api_provider");
    if (!p.containsKey("name") && !p.containsKey("id") && !p.containsKey("wallet")) p.put("name", "x402 resource server");
    return new X402Observer(client, X402Mapper.ADAPTER_SERVER, p);
  }

  private static String key(String url) {
    int hash = url.indexOf('#');
    return hash < 0 ? url : url.substring(0, hash);
  }

  private void report(RuntimeException e, String what) {
    client.notify(new HalvorError(HalvorError.INVALID_EVENT, e.getMessage() == null ? what + " failed" : e.getMessage(),
        null, null, null, null));
  }

  /**
   * Call with a request's URL and a header lookup before it is sent (client) or handled
   * (server). Returns the decoded payment payload to pass to {@link #onResponse}, or null.
   */
  public Map<String, Object> onRequest(String url, Function<String, String> header, AgentContext ctx) {
    String signature;
    try {
      signature = header.apply(X402Headers.PAYMENT_SIGNATURE);
    } catch (RuntimeException e) {
      return null;
    }
    if (signature == null || signature.isEmpty()) return null;
    Map<String, Object> payload;
    try {
      payload = X402Headers.decode(signature);
    } catch (RuntimeException e) {
      report(e, "PAYMENT-SIGNATURE decode");
      return null;
    }
    try {
      String previous = null;
      if (payee == null) {
        synchronized (requested) {
          previous = requested.remove(key(url));
        }
      }
      Map<String, Object> mapped = X402Mapper.mapPaymentPayload(payload, adapter, payee, previous, ctx);
      if (mapped != null) client.emit(mapped, ctx);
    } catch (RuntimeException e) {
      report(e, "PAYMENT-SIGNATURE mapping");
    }
    return payload;
  }

  /** Call with the response's status and a header lookup, and what onRequest returned. */
  public void onResponse(String url, int status, Function<String, String> header, Map<String, Object> payload, AgentContext ctx) {
    try {
      if (status == 402) {
        String required = header.apply(X402Headers.PAYMENT_REQUIRED);
        if (required != null && !required.isEmpty()) {
          try {
            Map<String, Object> mapped = X402Mapper.mapPaymentRequired(X402Headers.decode(required), adapter, payee, ctx);
            if (mapped != null) {
              String id = client.emit(mapped, ctx);
              if (payee == null) {
                synchronized (requested) {
                  requested.remove(key(url));
                  requested.put(key(url), id);
                }
              }
            }
          } catch (RuntimeException e) {
            report(e, "PAYMENT-REQUIRED");
          }
        }
      }
      String response = header.apply(X402Headers.PAYMENT_RESPONSE);
      if (response != null && !response.isEmpty()) {
        try {
          X402Mapper.SettlementContext sc = X402Mapper.SettlementContext.from(payload).withPayee(payee);
          Map<String, Object> mapped = X402Mapper.mapSettlementResponse(X402Headers.decode(response), sc, adapter, ctx);
          if (mapped != null) client.emit(mapped, ctx);
        } catch (RuntimeException e) {
          report(e, "PAYMENT-RESPONSE");
        }
      }
    } catch (RuntimeException e) {
      report(e, "x402 response");
    }
  }
}
