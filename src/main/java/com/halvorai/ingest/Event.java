package com.halvorai.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The input to {@link HalvorClient#emit}. The client fills ape_version, id, occurred_at,
 * environment and source, and takes agent, owner and instruction from the {@link AgentContext}
 * when the event leaves them out. Field reference: https://schema.halvorai.com
 *
 * <pre>{@code
 * Event.of(Event.PAYMENT_SETTLED,
 *     Payment.of("pi_3QxAbCdEfGhIjKlM", "stripe", Amount.minor("41200", "USD"), "settled")
 *         .railRef("payment_intent", "pi_3QxAbCdEfGhIjKlM"),
 *     Payee.of("merchant").name("Example Airways"))
 * }</pre>
 */
public final class Event extends ApeObject<Event> {
  public static final String PAYMENT_REQUESTED = "payment.requested";
  public static final String PAYMENT_AUTHORIZED = "payment.authorized";
  public static final String PAYMENT_SETTLED = "payment.settled";
  public static final String PAYMENT_FAILED = "payment.failed";
  public static final String PAYMENT_REFUNDED = "payment.refunded";
  public static final String PAYMENT_DISPUTED = "payment.disputed";

  private Event() {}

  /** An event of the given type for a payment and its payee. */
  public static Event of(String type, Payment payment, Payee payee) {
    return new Event().set("type", type).set("payment", payment).set("payee", payee);
  }

  /** The id; generated when not set. Set it to make retries idempotent. */
  public Event id(String id) { return set("id", id); }
  /** RFC 3339 UTC with milliseconds; the time of emit when not set. */
  public Event occurredAt(String occurredAt) { return set("occurred_at", occurredAt); }
  /** "test" or "live"; the client's when not set. */
  public Event environment(String environment) { return set("environment", environment); }
  /** The adapter name (default "custom"). The source id and SDK version come from the client. */
  public Event adapter(String adapter) { return set("source", Map.of("adapter", adapter)); }
  public Event agent(Agent agent) { return set("agent", agent); }
  public Event owner(Owner owner) { return set("owner", owner); }
  public Event instruction(Instruction instruction) { return set("instruction", instruction); }
  public Event instrument(Instrument instrument) { return set("instrument", instrument); }
  public Event related(Related related) { return set("related", related); }

  /** Adds an evidence item (at most 16). */
  @SuppressWarnings("unchecked")
  public Event evidence(Evidence evidence) {
    List<Object> list = (List<Object>) toMap().getOrDefault("evidence", new ArrayList<>());
    list.add(evidence.toMap());
    return set("evidence", list);
  }

  /** Adds a label (at most 20; keys up to 64 characters, values up to 256). */
  @SuppressWarnings("unchecked")
  public Event label(String key, String value) {
    Map<String, Object> labels = (Map<String, Object>) toMap().getOrDefault("labels", new LinkedHashMap<>());
    labels.put(key, value);
    return set("labels", labels);
  }
}
