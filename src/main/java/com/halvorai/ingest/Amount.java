package com.halvorai.ingest;

/** An integer string in minor units (with a currency) or atomic units (with an asset). */
public final class Amount extends ApeObject<Amount> {
  private Amount() {}

  /** Minor units of an ISO 4217 currency: "41200", "USD" is $412.00. */
  public static Amount minor(String value, String currency) {
    return new Amount().set("value", value).set("unit", "minor").set("currency", currency);
  }

  /** Atomic units of an on-chain token. */
  public static Amount atomic(String value, Asset asset) {
    return new Amount().set("value", value).set("unit", "atomic").set("asset", asset);
  }

  public Amount currency(String currency) { return set("currency", currency); }
  public Amount asset(Asset asset) { return set("asset", asset); }
}
