package com.halvorai.ingest;

/** The person or business the agent acts for. */
public final class Owner extends ApeObject<Owner> {
  private Owner() {}

  /** type is "person" or "business". */
  public static Owner of(String id, String type) { return new Owner().set("id", id).set("type", type); }

  public Owner displayName(String displayName) { return set("display_name", displayName); }
  /** ISO 3166-1 alpha-2, such as "US". */
  public Owner country(String country) { return set("country", country); }
  public Owner kycRef(String kycRef) { return set("kyc_ref", kycRef); }

  String id() { return (String) toMap().get("id"); }
  String type() { return (String) toMap().get("type"); }
}
