package com.halvorai.ingest;

import com.halvorai.ingest.jcs.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Helpers shared by the tests. */
public final class TestSupport {
  private TestSupport() {}

  /** A test resource as text, such as "conformance/events.json". */
  public static String resource(String name) {
    try (InputStream in = TestSupport.class.getClassLoader().getResourceAsStream(name)) {
      if (in == null) throw new IllegalArgumentException("missing test resource " + name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static Map<String, Object> json(String name) {
    return Json.parseObject(resource(name));
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> map(Object v) {
    return (Map<String, Object>) v;
  }

  @SuppressWarnings("unchecked")
  public static List<Object> list(Object v) {
    return (List<Object>) v;
  }
}
