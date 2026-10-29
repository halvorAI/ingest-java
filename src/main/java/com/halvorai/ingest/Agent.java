package com.halvorai.ingest;

/** The AI agent that made the payment. */
public final class Agent extends ApeObject<Agent> {
  private Agent() {}

  public static Agent of(String id) { return new Agent().set("id", id); }

  public Agent name(String name) { return set("name", name); }
  public Agent framework(String framework) { return set("framework", framework); }
  public Agent version(String version) { return set("version", version); }
  public Agent keyThumbprint(String keyThumbprint) { return set("key_thumbprint", keyThumbprint); }

  /** A know-your-agent attestation reference. */
  public Agent kya(String provider, String subject, String assurance, String verifiedAt) {
    java.util.Map<String, Object> kya = new java.util.LinkedHashMap<>();
    kya.put("provider", provider);
    kya.put("subject", subject);
    if (assurance != null) kya.put("assurance", assurance);
    if (verifiedAt != null) kya.put("verified_at", verifiedAt);
    return set("kya", kya);
  }

  String id() { return (String) toMap().get("id"); }
}
