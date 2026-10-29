package com.halvorai.ingest;

import com.halvorai.ingest.jcs.Jcs;
import com.halvorai.ingest.jcs.Json;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An Ed25519 key in JSON Web Key form (RFC 8037). A source key has {@code d}; its public half,
 * which you register with HalvorAI, does not. {@code kid} is the source id.
 *
 * @param kty always "OKP"
 * @param crv always "Ed25519"
 * @param x the public key, base64url
 * @param d the private key seed, base64url, or null for a public key
 * @param kid the source id, such as the src_ id HalvorAI returned when you registered the key
 */
public record Jwk(String kty, String crv, String x, String d, String kid) {
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64D = Base64.getUrlDecoder();
  // DER prefixes for a raw 32-byte Ed25519 key (RFC 8410).
  private static final byte[] PKCS8_PREFIX = hex("302e020100300506032b657004220420");
  private static final byte[] X509_PREFIX = hex("302a300506032b6570032100");

  /** Makes a new Ed25519 source key with the given kid. Keep the private key secret. */
  public static Jwk generate(String kid) {
    try {
      KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      byte[] priv = pair.getPrivate().getEncoded();
      byte[] pub = pair.getPublic().getEncoded();
      byte[] seed = Arrays.copyOfRange(priv, priv.length - 32, priv.length);
      byte[] x = Arrays.copyOfRange(pub, pub.length - 32, pub.length);
      return new Jwk("OKP", "Ed25519", B64.encodeToString(x), B64.encodeToString(seed), kid);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Ed25519 is not available in this JVM", e);
    }
  }

  /** Reads a JWK from JSON, such as the HALVOR_SOURCE_KEY environment variable. */
  public static Jwk parse(String json) {
    Map<String, Object> m = Json.parseObject(json);
    return new Jwk(str(m, "kty"), str(m, "crv"), str(m, "x"), str(m, "d"), str(m, "kid"));
  }

  private static String str(Map<String, Object> m, String key) {
    return m.get(key) instanceof String s ? s : null;
  }

  /** The key without its private part. */
  public Jwk toPublic() {
    return new Jwk(kty, crv, x, null, kid);
  }

  /** Whether this is a usable private Ed25519 key with a kid. */
  public boolean isPrivate() {
    try {
      privateKey();
      return kid != null && !kid.isEmpty();
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }

  /** The key as JSON. */
  public String toJson() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("kty", kty);
    m.put("crv", crv);
    m.put("x", x);
    if (d != null) m.put("d", d);
    if (kid != null) m.put("kid", kid);
    return Jcs.canonicalize(m);
  }

  @Override
  public String toString() {
    return "Jwk[kty=" + kty + ", crv=" + crv + ", x=" + x + ", kid=" + kid + (d == null ? "]" : ", d=<redacted>]");
  }

  PrivateKey privateKey() throws GeneralSecurityException {
    checkType();
    if (d == null) throw new GeneralSecurityException("the key has no private part (d)");
    byte[] seed = B64D.decode(d);
    if (seed.length != 32) throw new GeneralSecurityException("d must be 32 bytes");
    return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(concat(PKCS8_PREFIX, seed)));
  }

  PublicKey publicKey() throws GeneralSecurityException {
    checkType();
    if (x == null) throw new GeneralSecurityException("the key has no public part (x)");
    byte[] raw = B64D.decode(x);
    if (raw.length != 32) throw new GeneralSecurityException("x must be 32 bytes");
    return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(concat(X509_PREFIX, raw)));
  }

  private void checkType() throws GeneralSecurityException {
    if (!"OKP".equals(kty) || !"Ed25519".equals(crv)) {
      throw new GeneralSecurityException("the key must be an Ed25519 JWK (kty OKP, crv Ed25519)");
    }
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  private static byte[] hex(String s) {
    byte[] out = new byte[s.length() / 2];
    for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
    return out;
  }
}
