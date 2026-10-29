package com.halvorai.ingest;

/** What the agent paid with. */
public final class Instrument extends ApeObject<Instrument> {
  private Instrument() {}

  /** type is card, agentic_token, stripe_spt, wallet or bank_account. */
  public static Instrument of(String type) { return new Instrument().set("type", type); }

  public Instrument network(String network) { return set("network", network); }
  public Instrument last4(String last4) { return set("last4", last4); }
  public Instrument par(String par) { return set("par", par); }
  public Instrument tokenRef(String tokenRef) { return set("token_ref", tokenRef); }
  public Instrument wallet(Wallet wallet) { return set("wallet", wallet); }
  public Instrument issuer(String issuer) { return set("issuer", issuer); }
}
