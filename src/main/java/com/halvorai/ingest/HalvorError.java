package com.halvorai.ingest;

/**
 * A problem reported through {@link HalvorClient.Builder#onError}. {@code emit} never throws;
 * everything that goes wrong later is reported here.
 *
 * @param code config, invalid_event, rejected, unauthorized, delivery_failed or spool
 * @param message what happened
 * @param serverCode the API's error code, such as invalid_signature or unknown_source, or null
 * @param requestId the API request id, or null
 * @param eventId the event concerned, or null
 * @param details validation problems or the API's details, or null
 */
public record HalvorError(String code, String message, String serverCode, String requestId, String eventId, Object details) {
  public static final String CONFIG = "config";
  public static final String INVALID_EVENT = "invalid_event";
  public static final String REJECTED = "rejected";
  public static final String UNAUTHORIZED = "unauthorized";
  public static final String DELIVERY_FAILED = "delivery_failed";
  public static final String SPOOL = "spool";

  static HalvorError of(String code, String message) {
    return new HalvorError(code, message, null, null, null, null);
  }

  HalvorError withCode(String newCode) {
    return new HalvorError(newCode, message, serverCode, requestId, eventId, details);
  }

  HalvorError withEventId(String newEventId) {
    return new HalvorError(code, message, serverCode, requestId, newEventId, details);
  }

  @Override
  public String toString() {
    return "halvorai: " + code + (serverCode == null ? "" : " (" + serverCode + ")") + (message == null ? "" : ": " + message);
  }
}
