package com.halvorai.ingest;

import java.util.Map;

/**
 * A signed event as sent to HalvorAI: the event and its detached JWS.
 *
 * @param event the event as a JSON map
 * @param sig the detached JWS ({@code <header>..<signature>})
 */
public record Envelope(Map<String, Object> event, String sig) {
  /** Whether the signature verifies with the source's public key. */
  public boolean verify(Jwk publicKey) {
    return Ape.verifyEvent(event, sig, publicKey);
  }
}
