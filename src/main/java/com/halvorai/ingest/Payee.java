package com.halvorai.ingest;

/** Who was paid. It needs at least one of name, id or wallet. */
public final class Payee extends ApeObject<Payee> {
  private Payee() {}

  /** type is merchant, api_provider, wallet, business or person. */
  public static Payee of(String type) { return new Payee().set("type", type); }

  public Payee name(String name) { return set("name", name); }
  public Payee id(String id) { return set("id", id); }
  public Payee website(String website) { return set("website", website); }
  public Payee country(String country) { return set("country", country); }
  public Payee mcc(String mcc) { return set("mcc", mcc); }
  public Payee wallet(Wallet wallet) { return set("wallet", wallet); }
  public Payee resourceUrl(String resourceUrl) { return set("resource_url", resourceUrl); }
}
