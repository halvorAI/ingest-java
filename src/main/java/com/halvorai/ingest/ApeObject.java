package com.halvorai.ingest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base of the typed event builders. Each one holds its fields as a JSON object; a null value
 * leaves the field out, because APE v0.1 never allows null.
 *
 * @param <T> the concrete type, for chaining
 */
public abstract class ApeObject<T extends ApeObject<T>> {
  private final Map<String, Object> fields = new LinkedHashMap<>();

  ApeObject() {}

  @SuppressWarnings("unchecked")
  private T self() {
    return (T) this;
  }

  /** Sets any field, including ones this builder has no method for. Null removes the field. */
  public T set(String key, Object value) {
    if (value == null) fields.remove(key);
    else fields.put(key, value);
    return self();
  }

  /** The object as a JSON map, a deep copy. */
  public Map<String, Object> toMap() {
    @SuppressWarnings("unchecked")
    Map<String, Object> out = (Map<String, Object>) copy(fields);
    return out;
  }

  static Object copy(Object value) {
    if (value instanceof ApeObject<?> o) return o.toMap();
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (e.getValue() != null) out.put(String.valueOf(e.getKey()), copy(e.getValue()));
      }
      return out;
    }
    if (value instanceof Collection<?> c) {
      var out = new ArrayList<Object>(c.size());
      for (Object item : c) out.add(copy(item));
      return out;
    }
    return value;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + toMap();
  }
}
