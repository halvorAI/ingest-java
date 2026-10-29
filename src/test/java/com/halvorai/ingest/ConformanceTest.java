package com.halvorai.ingest;

import static com.halvorai.ingest.TestSupport.json;
import static com.halvorai.ingest.TestSupport.list;
import static com.halvorai.ingest.TestSupport.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.halvorai.ingest.jcs.Jcs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The ape-schema conformance data: the same verdicts and bytes as every other APE implementation.
 * src/test/resources/conformance is copied from
 * https://github.com/halvorAI/ape-schema/tree/main/conformance.
 */
class ConformanceTest {
  static String verdict(Map<String, Object> event) {
    List<Ape.Problem> problems = Ape.validateEvent(event);
    if (problems.isEmpty()) return "valid";
    for (Ape.Problem p : problems) {
      if (Ape.Problem.PAN_DETECTED.equals(p.message())) return "pan";
    }
    return "schema";
  }

  @Test
  void events() {
    List<Object> cases = list(json("conformance/events.json").get("cases"));
    assertEquals(40, cases.size());
    List<String> failures = new ArrayList<>();
    for (Object raw : cases) {
      Map<String, Object> c = map(raw);
      String want = "valid".equals(c.get("expect")) ? "valid" : (String) c.get("rule");
      String got = verdict(map(c.get("event")));
      if (!want.equals(got)) failures.add(c.get("name") + ": got " + got + ", want " + want + " " + Ape.validateEvent(map(c.get("event"))));
    }
    assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
  }

  /** The cross-language signing vector shared with the Node.js, Python and Go SDKs. */
  @Test
  void signVector() {
    Map<String, Object> vector = json("conformance/sign-vector.json");
    Map<String, Object> event = map(vector.get("event"));
    assertEquals(vector.get("canonical_jcs"), Jcs.canonicalize(event));
    Jwk privateKey = Jwk.parse(Jcs.canonicalize(vector.get("private_jwk")));
    Jwk publicKey = Jwk.parse(Jcs.canonicalize(vector.get("public_jwk")));
    String sig = Ape.signEvent(event, privateKey);
    assertEquals(vector.get("sig"), sig);
    assertTrue(Ape.verifyEvent(event, sig, publicKey));
    assertFalse(Ape.verifyEvent(event, sig.replace("..", ".x."), publicKey));
    event.put("id", "evt_tampered000000000000000");
    assertFalse(Ape.verifyEvent(event, sig, publicKey));
  }

  @Test
  void generatedKeysSignAndVerify() {
    Jwk key = Jwk.generate("src_local");
    assertTrue(key.isPrivate());
    assertFalse(key.toPublic().isPrivate());
    assertFalse(key.toString().contains(key.d()));
    Map<String, Object> event = map(vector().get("event"));
    event.put("source", Map.of("id", "src_local", "sdk", "x", "adapter", "custom"));
    assertTrue(Ape.verifyEvent(event, Ape.signEvent(event, key), key.toPublic()));
    assertTrue(Ape.newEventId().matches("evt_[0-9A-HJKMNP-TV-Z]{26}"));
    assertTrue(Ape.nowUtc().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
  }

  private static Map<String, Object> vector() {
    return json("conformance/sign-vector.json");
  }
}
