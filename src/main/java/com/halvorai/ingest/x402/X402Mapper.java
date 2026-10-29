package com.halvorai.ingest.x402;

import com.halvorai.ingest.AgentContext;
import com.halvorai.ingest.Ape;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps x402 v2 payloads to partial APE events, the same way as the Node.js, Python and Go SDKs
 * (ape-schema docs/MAPPINGS.md §5.1):
 *
 * <ul>
 *   <li>payment.requested has payment_id {@code x402:<network>:requested:<event id>}, so every
 *       402 challenge is its own payment request.
 *   <li>Every later stage uses {@code x402:<network>:<nonce>}, never the transaction hash, so
 *       authorization, settlement and failure join. The authorized event links to its challenge
 *       with related.previous_event_id.
 *   <li>A missing network or asset is never guessed: the mapper throws and the adapter reports
 *       invalid_event instead of recording a testnet or a zero address.
 * </ul>
 */
public final class X402Mapper {
  private X402Mapper() {}

  public static final String ADAPTER_CLIENT = "x402-client";
  public static final String ADAPTER_SERVER = "x402-server";

  /** What a settlement response alone does not say, taken from the payment payload. */
  public record SettlementContext(Map<String, Object> accept, String nonce, Map<String, Object> resource, Map<String, Object> payee) {
    /** The context of a decoded payment payload, or an empty context for null. */
    public static SettlementContext from(Map<String, Object> payload) {
      if (payload == null) return new SettlementContext(null, null, null, null);
      Map<String, Object> accept = payload.get("accepted") instanceof Map ? obj(payload.get("accepted")) : null;
      Map<String, Object> resource = payload.get("resource") instanceof Map ? obj(payload.get("resource")) : null;
      return new SettlementContext(accept, str(obj(obj(payload.get("payload")).get("authorization")).get("nonce")), resource, null);
    }

    public SettlementContext withPayee(Map<String, Object> newPayee) {
      return new SettlementContext(accept, nonce, resource, newPayee);
    }
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> obj(Object v) {
    return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  static String str(Object v) {
    return v instanceof String s && !s.isEmpty() ? s : null;
  }

  private static String orUnknown(String s) {
    return s == null ? "unknown" : s;
  }

  private static String truncate(String s, int n) {
    return s.length() <= n ? s : s.substring(0, n);
  }

  private static String host(String url) {
    if (url == null) return null;
    try {
      URI u = URI.create(url);
      if (u.getHost() == null) return null;
      return u.getPort() == -1 ? u.getHost() : u.getHost() + ":" + u.getPort();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Sets agent and owner only when the context does not; the client fills them otherwise. */
  private static void attribution(Map<String, Object> out, AgentContext ctx, String payerWallet) {
    if (ctx == null || ctx.agent() == null) out.put("agent", new LinkedHashMap<>(Map.of("id", payerWallet == null ? "unattributed" : payerWallet)));
    if (ctx == null || ctx.owner() == null) out.put("owner", new LinkedHashMap<>(Map.of("id", "unattributed", "type", "business")));
  }

  private static Map<String, Object> asset(Map<String, Object> accept) {
    String network = str(accept.get("network"));
    if (network == null) throw new IllegalArgumentException("x402 payment requirement has no network; event not recorded");
    String address = str(accept.get("asset"));
    if (address == null) throw new IllegalArgumentException("x402 payment requirement has no asset; event not recorded");
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("network", network);
    out.put("address", address);
    Map<String, Object> extra = obj(accept.get("extra"));
    String name = str(extra.get("name"));
    if (name != null) out.put("symbol", truncate(name, 16));
    if (extra.get("decimals") instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
      out.put("decimals", n.longValue());
    }
    return out;
  }

  private static Map<String, Object> amount(Map<String, Object> accept, String override) {
    Map<String, Object> a = asset(accept);
    String value = override != null ? override : str(accept.get("amount"));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("value", value == null ? "0" : value);
    out.put("unit", "atomic");
    out.put("asset", a);
    return out;
  }

  private static Map<String, Object> payee(Map<String, Object> accept, Map<String, Object> resource, Map<String, Object> override) {
    if (override != null) return new LinkedHashMap<>(override);
    String url = str(resource.get("url"));
    String name = str(resource.get("serviceName"));
    if (name == null) name = str(resource.get("description"));
    if (name == null) name = host(url);
    Map<String, Object> payee = new LinkedHashMap<>();
    payee.put("type", url != null ? "api_provider" : "wallet");
    if (name != null) payee.put("name", truncate(name, 256));
    if (url != null) payee.put("resource_url", url);
    String payTo = str(accept.get("payTo"));
    String network = str(accept.get("network"));
    if (payTo != null && network != null) payee.put("wallet", Map.of("network", network, "address", payTo));
    if (!payee.containsKey("name") && !payee.containsKey("wallet")) payee.put("name", "x402 payee");
    return payee;
  }

  private static Map<String, Object> railRef(Map<String, Object> accept, Map<String, Object> extra) {
    Map<String, Object> ref = new LinkedHashMap<>();
    String scheme = str(accept.get("scheme"));
    if (scheme != null) ref.put("scheme", scheme);
    String network = str(accept.get("network"));
    if (network != null) ref.put("network", network);
    String flow = str(obj(accept.get("extra")).get("paymentFlow"));
    if (flow != null) ref.put("payment_flow", flow);
    if (extra != null) ref.putAll(extra);
    return ref;
  }

  private static void description(Map<String, Object> payment, Map<String, Object> resource) {
    String d = str(resource.get("description"));
    if (d != null) payment.put("description", truncate(d, 256));
  }

  private static List<Object> evidence(String kind, Map<String, Object> value) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("kind", kind);
    e.put("format", "json");
    e.put("value", value);
    e.put("sha256", Ape.evidenceSha256(value));
    return new ArrayList<>(List.of(e));
  }

  private static Map<String, Object> payment(String id, Map<String, Object> amount, String status, Map<String, Object> railRef) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("payment_id", id);
    p.put("rail", "x402");
    p.put("amount", amount);
    p.put("status", status);
    p.put("rail_ref", railRef);
    return p;
  }

  /**
   * Maps a decoded PAYMENT-REQUIRED header (a 402 challenge) to a payment.requested event with
   * its own id. Returns null when there is nothing to map. payee replaces the payee read from the
   * challenge (servers pass their own identity).
   */
  public static Map<String, Object> mapPaymentRequired(Map<String, Object> required, String adapter,
      Map<String, Object> payee, AgentContext ctx) {
    if (!(required.get("accepts") instanceof List<?> accepts) || accepts.isEmpty() || !(accepts.get(0) instanceof Map)) return null;
    Map<String, Object> accept = obj(accepts.get(0));
    Map<String, Object> amount = amount(accept, null);
    Map<String, Object> resource = obj(required.get("resource"));
    String id = Ape.newEventId();
    Map<String, Object> p = payment("x402:" + orUnknown(str(accept.get("network"))) + ":requested:" + id, amount, "requested", railRef(accept, null));
    description(p, resource);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", id);
    out.put("type", "payment.requested");
    out.put("source", Map.of("adapter", adapter));
    out.put("payment", p);
    out.put("payee", payee(accept, resource, payee));
    out.put("evidence", new ArrayList<>());
    attribution(out, ctx, null);
    return out;
  }

  /**
   * Maps a decoded PAYMENT-SIGNATURE header to a payment.authorized event. previousEventId is the
   * id of the payment.requested event it answers, or null.
   */
  public static Map<String, Object> mapPaymentPayload(Map<String, Object> payload, String adapter,
      Map<String, Object> payee, String previousEventId, AgentContext ctx) {
    if (!(payload.get("accepted") instanceof Map)) return null;
    Map<String, Object> accept = obj(payload.get("accepted"));
    Map<String, Object> auth = obj(obj(payload.get("payload")).get("authorization"));
    String nonce = orUnknown(str(auth.get("nonce")));
    String payer = str(auth.get("from"));
    Map<String, Object> amount = amount(accept, str(auth.get("value")));
    Map<String, Object> p = payment("x402:" + orUnknown(str(accept.get("network"))) + ":" + nonce, amount, "authorized",
        railRef(accept, Map.of("nonce", nonce)));
    Map<String, Object> resource = obj(payload.get("resource"));
    description(p, resource);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("type", "payment.authorized");
    out.put("source", Map.of("adapter", adapter));
    out.put("payment", p);
    out.put("payee", payee(accept, resource, payee));
    out.put("evidence", evidence("x402.payment_payload", payload));
    attribution(out, ctx, payer);
    String network = str(accept.get("network"));
    if (payer != null && network != null) {
      out.put("instrument", Map.of("type", "wallet", "wallet", Map.of("network", network, "address", payer)));
    }
    if (previousEventId != null) out.put("related", Map.of("previous_event_id", previousEventId));
    return out;
  }

  /** Maps a decoded PAYMENT-RESPONSE header to payment.settled or payment.failed. */
  public static Map<String, Object> mapSettlementResponse(Map<String, Object> settlement, SettlementContext sc,
      String adapter, AgentContext ctx) {
    Map<String, Object> merged = new LinkedHashMap<>();
    merged.put("scheme", "exact");
    if (sc.accept() != null) {
      merged.putAll(sc.accept());
    } else {
      if (str(settlement.get("network")) != null) merged.put("network", settlement.get("network"));
      if (str(settlement.get("amount")) != null) merged.put("amount", settlement.get("amount"));
    }
    String network = str(settlement.get("network"));
    if (network == null) network = orUnknown(str(merged.get("network")));
    boolean success = Boolean.TRUE.equals(settlement.get("success"));
    String payer = str(settlement.get("payer"));
    Map<String, Object> extra = new LinkedHashMap<>();
    if (sc.nonce() != null) extra.put("nonce", sc.nonce());
    String tx = str(settlement.get("transaction"));
    if (tx != null) extra.put("tx_hash", tx);
    String override = str(settlement.get("amount"));
    if (override == null) override = str(merged.get("amount"));
    Map<String, Object> amount = amount(merged, override);
    Map<String, Object> p = payment("x402:" + network + ":" + orUnknown(sc.nonce()), amount, success ? "settled" : "failed",
        railRef(merged, extra));
    if (!success) {
      String reason = str(settlement.get("errorReason"));
      p.put("failure_reason", truncate(reason == null ? "settlement_failed" : reason, 128));
    }
    Map<String, Object> resource = sc.resource() == null ? Map.of() : sc.resource();
    description(p, resource);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("type", success ? "payment.settled" : "payment.failed");
    out.put("source", Map.of("adapter", adapter));
    out.put("payment", p);
    out.put("payee", payee(merged, resource, sc.payee()));
    out.put("evidence", evidence("x402.settlement", settlement));
    attribution(out, ctx, payer);
    if (payer != null && !network.equals("unknown")) {
      out.put("instrument", Map.of("type", "wallet", "wallet", Map.of("network", network, "address", payer)));
    }
    return out;
  }
}
