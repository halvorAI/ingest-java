package com.halvorai.ingest;

/** An on-chain token on a CAIP-2 network. */
public final class Asset extends ApeObject<Asset> {
  private Asset() {}

  public static Asset of(String network, String address) { return new Asset().set("network", network).set("address", address); }

  public Asset symbol(String symbol) { return set("symbol", symbol); }
  public Asset decimals(int decimals) { return set("decimals", decimals); }
}
