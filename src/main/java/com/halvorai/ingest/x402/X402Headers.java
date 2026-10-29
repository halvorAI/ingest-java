package com.halvorai.ingest.x402;

import com.halvorai.ingest.jcs.Jcs;
import com.halvorai.ingest.jcs.Json;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** The x402 v2 HTTP transport headers: base64 JSON. */
public final class X402Headers {
  private X402Headers() {}

  public static final String PAYMENT_REQUIRED = "PAYMENT-REQUIRED";
  public static final String PAYMENT_SIGNATURE = "PAYMENT-SIGNATURE";
  public static final String PAYMENT_RESPONSE = "PAYMENT-RESPONSE";

  /** Decodes a base64 (standard or URL-safe, padded or not) JSON object header. */
  public static Map<String, Object> decode(String value) {
    String trimmed = value == null ? "" : value.strip();
    if (trimmed.isEmpty()) throw new IllegalArgumentException("empty payment header");
    String b64 = trimmed.replace('-', '+').replace('_', '/').replaceAll("=+$", "");
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(b64 + "=".repeat((4 - b64.length() % 4) % 4));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("payment header is not valid base64");
    }
    try {
      return Json.parseObject(new String(raw, StandardCharsets.UTF_8));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("payment header is not a valid JSON object");
    }
  }

  /** Encodes a value as a base64 JSON header (for tests and servers). */
  public static String encode(Object value) {
    return Base64.getEncoder().encodeToString(Jcs.canonicalize(value).getBytes(StandardCharsets.UTF_8));
  }
}
