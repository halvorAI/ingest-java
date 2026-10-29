package com.halvorai.ingest;

/** Links an event to others. */
public final class Related extends ApeObject<Related> {
  private Related() {}

  public static Related create() { return new Related(); }

  public Related previousEventId(String id) { return set("previous_event_id", id); }
  public Related correctsEventId(String id) { return set("corrects_event_id", id); }
  public Related sessionId(String id) { return set("session_id", id); }
}
