package com.halvorai.ingest;

import com.halvorai.ingest.jcs.Jcs;
import com.halvorai.ingest.jcs.Json;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Agent Payment Event helpers: ids, timestamps, hashes, signing and validation. Every function
 * gives the same result as the HalvorAI Node.js, Python and Go SDKs.
 */
public final class Ape {
  private Ape() {}

  /** The APE schema version this SDK writes. */
  public static final String VERSION = "0.1";

  private static final String HEADER_TYP = "ape+jws";
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64D = Base64.getUrlDecoder();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final DateTimeFormatter UTC_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  /** A new event id: "evt_" and a 26-character ULID (time-ordered). */
  public static String newEventId() {
    char[] out = new char[26];
    long ms = System.currentTimeMillis();
    for (int i = 9; i >= 0; i--) {
      out[i] = CROCKFORD[(int) (ms & 31)];
      ms >>>= 5;
    }
    byte[] r = new byte[10];
    RANDOM.nextBytes(r);
    // 80 random bits as 16 characters of 5 bits.
    int bit = 0;
    for (int i = 10; i < 26; i++) {
      int v = 0;
      for (int k = 0; k < 5; k++, bit++) v = (v << 1) | ((r[bit / 8] >> (7 - bit % 8)) & 1);
      out[i] = CROCKFORD[v];
    }
    return "evt_" + new String(out);
  }

  /** The current time as APE writes it: RFC 3339, UTC, milliseconds. */
  public static String nowUtc() {
    return UTC_MILLIS.format(Instant.now());
  }

  /** Formats an instant as APE writes it. */
  public static String formatUtc(Instant instant) {
    return UTC_MILLIS.format(instant);
  }

  /** The instruction digest: "sha256:" and the hex SHA-256 of the UTF-8 text. */
  public static String hashInstruction(String text) {
    return "sha256:" + sha256Hex(text);
  }

  /** The evidence digest: UTF-8 bytes for a string, the canonical (JCS) form for anything else. */
  public static String evidenceSha256(Object value) {
    return "sha256:" + sha256Hex(value instanceof String s ? s : Jcs.canonicalize(value));
  }

  private static String sha256Hex(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sourceId(Map<String, Object> event) {
    if (event.get("source") instanceof Map<?, ?> source && source.get("id") instanceof String id && !id.isEmpty()) {
      return id;
    }
    throw new IllegalArgumentException("event.source.id is required to sign an event");
  }

  private static String protectedHeader(String kid) {
    String header = "{\"alg\":\"EdDSA\",\"kid\":" + Jcs.quote(kid) + ",\"typ\":\"ape+jws\",\"ape\":\"0.1\"}";
    return B64.encodeToString(header.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Signs the canonical (JCS) form of an event with the source's private key and returns a
   * compact JWS with a detached payload: {@code <header>..<signature>}. The header kid is
   * event.source.id.
   */
  public static String signEvent(Map<String, Object> event, Jwk key) {
    try {
      String header = protectedHeader(sourceId(event));
      String input = header + "." + B64.encodeToString(Jcs.canonicalize(event).getBytes(StandardCharsets.UTF_8));
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(key.privateKey());
      signer.update(input.getBytes(StandardCharsets.US_ASCII));
      return header + ".." + B64.encodeToString(signer.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("cannot sign with this key: " + e.getMessage(), e);
    }
  }

  /** Whether sig is a valid detached APE JWS over exactly this event. Never throws. */
  public static boolean verifyEvent(Map<String, Object> event, String sig, Jwk key) {
    try {
      String[] parts = sig.split("\\.", -1);
      if (parts.length != 3 || parts[0].isEmpty() || !parts[1].isEmpty() || parts[2].isEmpty()) return false;
      Map<String, Object> header = Json.parseObject(new String(B64D.decode(parts[0]), StandardCharsets.UTF_8));
      if (!"EdDSA".equals(header.get("alg"))
          || !HEADER_TYP.equals(header.get("typ"))
          || !VERSION.equals(header.get("ape"))
          || !sourceId(event).equals(header.get("kid"))) {
        return false;
      }
      String input = parts[0] + "." + B64.encodeToString(Jcs.canonicalize(event).getBytes(StandardCharsets.UTF_8));
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(key.publicKey());
      verifier.update(input.getBytes(StandardCharsets.US_ASCII));
      return verifier.verify(B64D.decode(parts[2]));
    } catch (RuntimeException | GeneralSecurityException e) {
      return false;
    }
  }

  /**
   * Checks a complete event against APE v0.1 and the card-number rule. Returns no problems when
   * the event is valid. Accepts and rejects exactly what https://schema.halvorai.com/ape/v0.1.json
   * does, as the other SDKs do.
   */
  public static List<Problem> validateEvent(Map<String, Object> event) {
    return Validator.validate(event);
  }

  /** One reason an event does not match APE v0.1. */
  public record Problem(String path, String message) {
    /** The message for a card number in a free-text field. */
    public static final String PAN_DETECTED = "pan_detected";

    @Override
    public String toString() {
      return path.isEmpty() ? message : path + ": " + message;
    }
  }
}
