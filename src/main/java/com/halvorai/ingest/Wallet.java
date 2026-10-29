package com.halvorai.ingest;

/** An address on a CAIP-2 network. */
public final class Wallet extends ApeObject<Wallet> {
  private Wallet() {}

  public static Wallet of(String network, String address) { return new Wallet().set("network", network).set("address", address); }
}
