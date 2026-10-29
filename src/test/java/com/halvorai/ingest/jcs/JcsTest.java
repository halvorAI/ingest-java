package com.halvorai.ingest.jcs;

import static com.halvorai.ingest.TestSupport.json;
import static com.halvorai.ingest.TestSupport.list;
import static com.halvorai.ingest.TestSupport.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JcsTest {
  /** The ape-schema vectors (https://github.com/halvorAI/ape-schema/tree/main/conformance). */
  @Test
  void conformanceVectors() {
    Map<String, Object> vectors = json("jcs/jcs-vectors.json");
    for (Object raw : list(vectors.get("numbers"))) {
      Map<String, Object> v = map(raw);
      double d = Double.longBitsToDouble(new BigInteger((String) v.get("bits"), 16).longValue());
      assertEquals(v.get("expected"), Jcs.formatNumber(d), "number " + v.get("bits"));
    }
    for (Object raw : list(vectors.get("objects"))) {
      Map<String, Object> v = map(raw);
      assertEquals(v.get("expected"), Jcs.transform((String) v.get("json")));
    }
  }

  /** Random values canonicalized by the TypeScript SDK's algorithm. */
  @Test
  void matchesTypeScript() {
    List<Object> cases = list(json("jcs/differential.json").get("cases"));
    assertEquals(1500, cases.size());
    for (Object raw : cases) {
      Map<String, Object> c = map(raw);
      assertEquals(c.get("expected"), Jcs.transform((String) c.get("json")), (String) c.get("json"));
    }
  }

  @Test
  void javaValues() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("z", List.of(1, 2.5, -3L));
    m.put("a", Map.of("b", 1));
    m.put("€", null);
    assertEquals("{\"a\":{\"b\":1},\"z\":[1,2.5,-3],\"€\":null}", Jcs.canonicalize(m));
    assertThrows(Jcs.JcsException.class, () -> Jcs.canonicalize(Double.NaN));
    assertThrows(Jcs.JcsException.class, () -> Jcs.canonicalize(Map.of(1, "x")));
    assertThrows(Jcs.JcsException.class, () -> Jcs.canonicalize(new Object()));
  }

  @Test
  void rejectsBadJson() {
    for (String bad : List.of("{", "[1,]", "01", "\"\u0001\"", "{\"a\" 1}", "1 2", "\"\\q\"", "tru", "\"\\u+123\"")) {
      assertThrows(Json.JsonException.class, () -> Json.parse(bad), bad);
    }
  }
}
