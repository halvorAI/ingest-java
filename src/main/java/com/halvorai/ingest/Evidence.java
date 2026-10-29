package com.halvorai.ingest;

/** A signed artefact behind the payment. Its sha256 is computed from the value when not set. */
public final class Evidence extends ApeObject<Evidence> {
  private Evidence() {}

  /** Evidence carried inline: a string, or a map for format "json". */
  public static Evidence of(String kind, String format, Object value) {
    return new Evidence().set("kind", kind).set("format", format).set("value", value);
  }

  /** Evidence stored elsewhere; sha256 is required. */
  public static Evidence atUri(String kind, String format, String uri, String sha256) {
    return new Evidence().set("kind", kind).set("format", format).set("uri", uri).set("sha256", sha256);
  }

  public Evidence sha256(String sha256) { return set("sha256", sha256); }
  public Evidence capturedAt(String capturedAt) { return set("captured_at", capturedAt); }
}
