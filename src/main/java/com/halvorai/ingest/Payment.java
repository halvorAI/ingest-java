package com.halvorai.ingest;

import java.util.LinkedHashMap;
import java.util.Map;

/** The payment itself. */
public final class Payment extends ApeObject<Payment> {
  private Payment() {}

  /**
   * A payment. rail is stripe, card, x402, mpp, stablecoin or bank_transfer; status is
   * requested, authorized, settled, failed, refunded or disputed.
   */
  public static Payment of(String paymentId, String rail, Amount amount, String status) {
    return new Payment().set("payment_id", paymentId).set("rail", rail).set("amount", amount)
        .set("status", status).set("rail_ref", new LinkedHashMap<String, Object>());
  }

  /** Adds one of the rail's own ids; only the keys defined for the rail are allowed. */
  @SuppressWarnings("unchecked")
  public Payment railRef(String key, Object value) {
    Map<String, Object> ref = (Map<String, Object>) toMap().get("rail_ref");
    ref.put(key, value);
    return set("rail_ref", ref);
  }

  /** Required when status is failed. */
  public Payment failureReason(String failureReason) { return set("failure_reason", failureReason); }
  public Payment description(String description) { return set("description", description); }
  public Payment category(String category) { return set("category", category); }
}
