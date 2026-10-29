package com.halvorai.ingest.stripe;

import com.halvorai.ingest.AgentContext;
import com.halvorai.ingest.Ape;
import com.halvorai.ingest.HalvorClient;
import com.halvorai.ingest.HalvorError;
import com.halvorai.ingest.jcs.Json;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Maps Stripe webhook events to Agent Payment Events, the same way as the HalvorAI Node.js, Python
 * and Go SDKs. No dependency on stripe-java.
 *
 * <p>Verify the webhook signature first, then pass the event on. With stripe-java:
 *
 * <pre>{@code
 * Event event = Webhook.constructEvent(payload, sigHeader, endpointSecret);
 * StripeEvents.emit(halvor, payload); // the raw body you just verified
 * }</pre>
 *
 * <p>Handled types: payment_intent.amount_capturable_updated, payment_intent.succeeded,
 * payment_intent.payment_failed, charge.refunded, charge.dispute.created,
 * issuing_authorization.created, issuing_authorization.updated and issuing_transaction.created.
 * Agents and owners are read from metadata.halvor_* (see {@link #tagMetadata}).
 */
public final class StripeEvents {
  private StripeEvents() {}

  /** The Stripe event types this class maps. */
  public static final List<String> HANDLED_TYPES = List.of(
      "payment_intent.amount_capturable_updated",
      "payment_intent.succeeded",
      "payment_intent.payment_failed",
      "charge.refunded",
      "charge.dispute.created",
      "issuing_authorization.created",
      "issuing_authorization.updated",
      "issuing_transaction.created");

  public static final String META_AGENT_ID = "halvor_agent_id";
  public static final String META_OWNER_ID = "halvor_owner_id";
  public static final String META_OWNER_TYPE = "halvor_owner_type";
  public static final String META_INSTRUCTION_ID = "halvor_instruction_id";

  private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
  private static final Pattern COUNTRY = Pattern.compile("^[A-Za-z]{2}$");
  private static final Pattern FOUR_DIGITS = Pattern.compile("^\\d{4}$");
  private static final Pattern HTTP = Pattern.compile("^(?i)https?://.*");

  /**
   * Maps a raw, signature-checked webhook body and queues it on the client. Returns the event id,
   * or empty when the type is not one HalvorAI records. Never throws.
   */
  public static Optional<String> emit(HalvorClient client, String payload) {
    try {
      Map<String, Object> mapped = map(payload);
      return mapped == null ? Optional.empty() : Optional.of(client.emit(mapped));
    } catch (RuntimeException e) {
      client.notify(new HalvorError(HalvorError.INVALID_EVENT, "stripe: " + e.getMessage(), null, null, null, null));
      return Optional.empty();
    }
  }

  /**
   * Like {@link #emit}, for an event already decoded, such as stripe-java's
   * {@code com.stripe.model.Event}: pass {@code event.getType()}, {@code event.getCreated()} and
   * {@code event.getDataObjectDeserializer().getRawJson()}.
   */
  public static Optional<String> emitObject(HalvorClient client, String type, long created, String objectJson) {
    try {
      Map<String, Object> mapped = mapObject(type, created, objectJson);
      return mapped == null ? Optional.empty() : Optional.of(client.emit(mapped));
    } catch (RuntimeException e) {
      client.notify(new HalvorError(HalvorError.INVALID_EVENT, "stripe: " + e.getMessage(), null, null, null, null));
      return Optional.empty();
    }
  }

  /**
   * Maps a raw webhook body to a partial APE event for {@link HalvorClient#emit}. Returns null
   * for types HalvorAI does not record.
   */
  public static Map<String, Object> map(String payload) {
    Map<String, Object> event = Json.parseObject(payload);
    String type = str(event.get("type"));
    long created = event.get("created") instanceof Number n ? n.longValue() : 0;
    if (!HANDLED_TYPES.contains(type)) return null;
    Object object = obj(event.get("data")).get("object");
    if (!(object instanceof Map)) throw new IllegalArgumentException("webhook has no data.object");
    return mapObject(type, created, obj(object));
  }

  /** Maps a type, created time (Unix seconds, 0 if unknown) and data.object JSON. */
  public static Map<String, Object> mapObject(String type, long created, String objectJson) {
    if (!HANDLED_TYPES.contains(type)) return null;
    return mapObject(type, created, Json.parseObject(objectJson));
  }

  private static Map<String, Object> mapObject(String type, long created, Map<String, Object> o) {
    return switch (type) {
      case "payment_intent.amount_capturable_updated" -> paymentIntent(created, o, "payment.authorized", "authorized");
      case "payment_intent.succeeded" -> paymentIntent(created, o, "payment.settled", "settled");
      case "payment_intent.payment_failed" -> paymentIntent(created, o, "payment.failed", "failed");
      case "charge.refunded" -> chargeRefunded(created, o);
      case "charge.dispute.created" -> dispute(created, o);
      case "issuing_authorization.created" -> issuingAuthorization(created, o, Boolean.FALSE.equals(o.get("approved")));
      case "issuing_authorization.updated" -> issuingAuthorization(created, o,
          Boolean.FALSE.equals(o.get("approved")) || "reversed".equals(o.get("status")) || "expired".equals(o.get("status")));
      case "issuing_transaction.created" -> issuingTransaction(created, o);
      default -> null;
    };
  }

  // --- helpers ---------------------------------------------------------------------------

  private static String str(Object v) {
    return v instanceof String s && !s.isEmpty() ? s : null;
  }

  private static String or(String s, String fallback) {
    return s != null ? s : fallback;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> obj(Object v) {
    return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  private static String truncate(String s, int n) {
    return s.length() <= n ? s : s.substring(0, n);
  }

  private static String currencyOf(Object v) {
    String c = v instanceof String s ? s.toUpperCase(Locale.ROOT) : "";
    return CURRENCY.matcher(c).matches() ? c : "USD";
  }

  private static double amountOf(Map<String, Object> o, String... keys) {
    for (String k : keys) {
      if (o.get(k) instanceof Number n && Double.isFinite(n.doubleValue())) return n.doubleValue();
    }
    return 0;
  }

  private static Map<String, Object> minor(double amount, String currency) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("value", new BigDecimal(amount).setScale(0, RoundingMode.DOWN).toPlainString());
    m.put("unit", "minor");
    m.put("currency", currency);
    return m;
  }

  private static Map<String, String> metadataOf(Map<String, Object> o) {
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : obj(o.get("metadata")).entrySet()) {
      if (e.getValue() instanceof String s) out.put(e.getKey(), s);
    }
    return out;
  }

  private static Map<String, String> issuingMeta(Map<String, Object> o) {
    Map<String, String> meta = metadataOf(o);
    meta.putAll(metadataOf(obj(o.get("card"))));
    return meta;
  }

  private static Map<String, Object> base(String type, String adapter, long created, Map<String, String> meta,
      Map<String, Object> payment, Map<String, Object> payee, Map<String, Object> o) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("type", type);
    out.put("source", Map.of("adapter", adapter));
    out.put("agent", new LinkedHashMap<>(Map.of("id", or(str(meta.get(META_AGENT_ID)), "unattributed"))));
    out.put("owner", new LinkedHashMap<>(Map.of("id", or(str(meta.get(META_OWNER_ID)), "unattributed"),
        "type", "person".equals(meta.get(META_OWNER_TYPE)) ? "person" : "business")));
    out.put("payment", payment);
    out.put("payee", payee);
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("kind", "stripe.object");
    evidence.put("format", "json");
    evidence.put("value", o);
    evidence.put("sha256", Ape.evidenceSha256(o));
    out.put("evidence", new java.util.ArrayList<>(List.of(evidence)));
    if (created != 0) out.put("occurred_at", Ape.formatUtc(Instant.ofEpochSecond(created)));
    String instructionId = str(meta.get(META_INSTRUCTION_ID));
    if (instructionId != null) {
      // Webhooks only carry the id; hash a stable local stand-in so the event validates.
      out.put("instruction", Map.of("id", instructionId, "hash", Ape.hashInstruction("stripe-meta:" + instructionId)));
    }
    return out;
  }

  private static Map<String, Object> payeeFromConnect(Map<String, Object> o) {
    String destination = or(str(obj(o.get("transfer_data")).get("destination")), str(o.get("on_behalf_of")));
    Map<String, Object> payee = new LinkedHashMap<>();
    payee.put("type", "merchant");
    if (destination != null) {
      payee.put("id", destination);
    } else {
      String d = str(o.get("description"));
      payee.put("name", d != null ? truncate(d, 256) : "Stripe merchant");
    }
    return payee;
  }

  private static Map<String, Object> payeeFromMerchantData(Map<String, Object> o) {
    Map<String, Object> payee = new LinkedHashMap<>();
    payee.put("type", "merchant");
    if (!(o.get("merchant_data") instanceof Map)) {
      payee.put("name", "Card merchant");
      return payee;
    }
    Map<String, Object> md = obj(o.get("merchant_data"));
    String name = str(md.get("name"));
    String id = str(md.get("network_id"));
    String country = str(md.get("country"));
    String mcc = str(md.get("category_code"));
    String url = str(md.get("url"));
    if (name != null) payee.put("name", name);
    if (id != null) payee.put("id", id);
    if (country != null && COUNTRY.matcher(country).matches()) payee.put("country", country.toUpperCase(Locale.ROOT));
    if (mcc != null && FOUR_DIGITS.matcher(mcc).matches()) payee.put("mcc", mcc);
    if (url != null && HTTP.matcher(url).matches()) payee.put("website", url);
    if (name == null && id == null) payee.put("name", "Card merchant");
    return payee;
  }

  private static String sharedPaymentToken(Map<String, Object> o) {
    Map<String, String> meta = metadataOf(o);
    return or(str(meta.get("shared_payment_token")), or(str(meta.get("halvor_shared_payment_token")), str(o.get("shared_payment_token"))));
  }

  private static Map<String, Object> payment(String id, String rail, double amount, String currency, String status,
      Map<String, Object> railRef) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("payment_id", id);
    p.put("rail", rail);
    p.put("amount", minor(amount, currency));
    p.put("status", status);
    p.put("rail_ref", railRef);
    return p;
  }

  // --- mappers ---------------------------------------------------------------------------

  private static Map<String, Object> paymentIntent(long created, Map<String, Object> o, String type, String status) {
    String id = or(str(o.get("id")), "pi_unknown");
    Map<String, Object> railRef = new LinkedHashMap<>(Map.of("payment_intent", id));
    Map<String, Object> p = payment(id, "stripe", amountOf(o, "amount"), currencyOf(o.get("currency")), status, railRef);
    String description = str(o.get("description"));
    if (description != null) p.put("description", truncate(description, 256));
    if (status.equals("failed")) {
      p.put("failure_reason", truncate(or(str(obj(o.get("last_payment_error")).get("code")), "payment_failed"), 128));
    }
    String spt = sharedPaymentToken(o);
    if (spt != null) railRef.put("shared_payment_token", spt);
    String account = str(o.get("on_behalf_of"));
    if (account != null) railRef.put("account", account);
    Map<String, Object> out = base(type, "stripe", created, metadataOf(o), p, payeeFromConnect(o), o);
    if (spt != null) out.put("instrument", Map.of("type", "stripe_spt"));
    return out;
  }

  private static Map<String, Object> chargeRefunded(long created, Map<String, Object> o) {
    String chargeId = or(str(o.get("id")), "ch_unknown");
    String pi = str(o.get("payment_intent"));
    Map<String, Object> railRef = new LinkedHashMap<>(Map.of("charge", chargeId));
    if (pi != null) railRef.put("payment_intent", pi);
    Map<String, Object> p = payment(or(pi, chargeId), "stripe", amountOf(o, "amount_refunded", "amount"),
        currencyOf(o.get("currency")), "refunded", railRef);
    String description = str(o.get("description"));
    if (description != null) p.put("description", truncate(description, 256));
    return base("payment.refunded", "stripe", created, metadataOf(o), p, payeeFromConnect(o), o);
  }

  private static Map<String, Object> dispute(long created, Map<String, Object> o) {
    String disputeId = or(str(o.get("id")), "dp_unknown");
    String pi = str(o.get("payment_intent"));
    String charge = str(o.get("charge"));
    Map<String, Object> railRef = new LinkedHashMap<>(Map.of("dispute", disputeId));
    if (pi != null) railRef.put("payment_intent", pi);
    if (charge != null) railRef.put("charge", charge);
    Map<String, Object> p = payment(or(pi, or(charge, disputeId)), "stripe", amountOf(o, "amount"),
        currencyOf(o.get("currency")), "disputed", railRef);
    String reason = str(o.get("reason"));
    if (reason != null) p.put("description", truncate(reason, 256));
    Map<String, Object> payee = new LinkedHashMap<>(Map.of("type", "merchant", "name", "Stripe dispute"));
    return base("payment.disputed", "stripe", created, metadataOf(o), p, payee, o);
  }

  private static void merchantDetails(Map<String, Object> p, Map<String, Object> merchant) {
    if (merchant.get("mcc") instanceof String mcc) p.put("category", "mcc:" + mcc);
    if (merchant.get("name") instanceof String name) p.put("description", truncate(name, 256));
  }

  private static Map<String, Object> issuingAuthorization(long created, Map<String, Object> o, boolean failed) {
    String id = or(str(o.get("id")), "iauth_unknown");
    Map<String, Object> merchant = payeeFromMerchantData(o);
    Map<String, Object> railRef = new LinkedHashMap<>();
    railRef.put("authorization_id", id);
    railRef.put("processor", "stripe-issuing");
    Map<String, Object> p = payment(id, "card", amountOf(o, "amount"), currencyOf(o.get("currency")),
        failed ? "failed" : "authorized", railRef);
    if (failed) p.put("failure_reason", "issuing_authorization_declined");
    merchantDetails(p, merchant);
    Map<String, Object> out = base(failed ? "payment.failed" : "payment.authorized", "stripe-issuing", created,
        issuingMeta(o), p, merchant, o);
    if (o.get("card") instanceof Map) {
      Map<String, Object> card = obj(o.get("card"));
      Map<String, Object> instrument = new LinkedHashMap<>();
      instrument.put("type", "card");
      instrument.put("issuer", "Stripe Issuing");
      String last4 = str(card.get("last4"));
      if (last4 != null && FOUR_DIGITS.matcher(last4).matches()) instrument.put("last4", last4);
      String brand = str(card.get("brand"));
      if (brand != null) instrument.put("network", brand.toLowerCase(Locale.ROOT));
      out.put("instrument", instrument);
    }
    return out;
  }

  private static Map<String, Object> issuingTransaction(long created, Map<String, Object> o) {
    String id = or(str(o.get("id")), "ipi_unknown");
    boolean refunded = "refund".equals(or(str(o.get("type")), "capture"));
    Map<String, Object> merchant = payeeFromMerchantData(o);
    Map<String, Object> railRef = new LinkedHashMap<>();
    railRef.put("transaction_id", id);
    railRef.put("processor", "stripe-issuing");
    String auth = str(o.get("authorization"));
    if (auth != null) railRef.put("authorization_id", auth);
    Map<String, Object> p = payment(id, "card", Math.abs(amountOf(o, "amount")), currencyOf(o.get("currency")),
        refunded ? "refunded" : "settled", railRef);
    merchantDetails(p, merchant);
    return base(refunded ? "payment.refunded" : "payment.settled", "stripe-issuing", created, issuingMeta(o), p, merchant, o);
  }

  // --- tagging ---------------------------------------------------------------------------

  /**
   * Returns a copy of metadata with metadata.halvor_* set from the current {@link AgentContext}.
   * Use it when you create a PaymentIntent or an Issuing card, so the webhooks for that payment
   * are attributed to the agent:
   *
   * <pre>{@code
   * params.putAllMetadata(StripeEvents.tagMetadata(Map.of("order", "1")));
   * }</pre>
   */
  public static Map<String, String> tagMetadata(Map<String, String> metadata) {
    return tagMetadata(metadata, null, null, null, null);
  }

  /** Like {@link #tagMetadata(Map)}; non-null arguments override the context. */
  public static Map<String, String> tagMetadata(Map<String, String> metadata, String agentId, String ownerId,
      String ownerType, String instructionId) {
    Map<String, String> out = new LinkedHashMap<>();
    if (metadata != null) out.putAll(metadata);
    AgentContext ctx = AgentContext.current();
    Map<String, Object> agent = ctx != null && ctx.agent() != null ? ctx.agent().toMap() : Map.of();
    Map<String, Object> owner = ctx != null && ctx.owner() != null ? ctx.owner().toMap() : Map.of();
    Map<String, Object> instruction = ctx != null && ctx.instruction() != null ? ctx.instruction().toMap() : Map.of();
    put(out, META_AGENT_ID, or(agentId, str(agent.get("id"))));
    put(out, META_OWNER_ID, or(ownerId, str(owner.get("id"))));
    put(out, META_OWNER_TYPE, or(ownerType, str(owner.get("type"))));
    put(out, META_INSTRUCTION_ID, or(instructionId, str(instruction.get("id"))));
    return out;
  }

  private static void put(Map<String, String> out, String key, String value) {
    if (value != null) out.put(key, value);
  }
}
