package com.halvorai.ingest;

/**
 * What the agent was asked to do. The text is hashed on your machine and never sent; only the
 * id, the hash and the other fields leave the process.
 */
public final class Instruction extends ApeObject<Instruction> {
  private Instruction() {}

  /** An instruction whose text is hashed locally. */
  public static Instruction of(String id, String text) { return new Instruction().set("id", id).set("text", text); }

  /** An instruction with a hash you computed ({@link Ape#hashInstruction}). */
  public static Instruction withHash(String id, String hash) { return new Instruction().set("id", id).set("hash", hash); }

  public Instruction summary(String summary) { return set("summary", summary); }
  /** chat, api, schedule, email, agent or other. */
  public Instruction channel(String channel) { return set("channel", channel); }
  public Instruction issuedAt(String issuedAt) { return set("issued_at", issuedAt); }
  public Instruction toolCallId(String toolCallId) { return set("tool_call_id", toolCallId); }

  String id() { return (String) toMap().get("id"); }
}
