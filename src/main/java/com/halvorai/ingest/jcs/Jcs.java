package com.halvorai.ingest.jcs;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * JSON Canonicalization Scheme (RFC 8785), byte-identical to the HalvorAI Node.js, Python and Go
 * SDKs.
 *
 * <p>Numbers are written as ECMAScript writes them, object members are sorted by UTF-16 code units
 * ({@link String#compareTo}), and strings are escaped like JavaScript's {@code JSON.stringify}: a
 * lone surrogate becomes a lowercase {@code \\udXXX} escape.
 *
 * <p>Accepted values: {@code null}, {@link Boolean}, {@link CharSequence}, {@link Number} (written
 * as a double, as JavaScript does), {@link Map} with string keys, {@link Collection}, and arrays of
 * objects.
 */
public final class Jcs {
  private Jcs() {}

  private static final int MAX_DEPTH = 1000;

  /** Thrown for a value that has no JSON form (NaN, infinity, a non-string key, an unknown type). */
  public static final class JcsException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public JcsException(String message) {
      super(message);
    }
  }

  /** Returns the canonical JSON text of a value. */
  public static String canonicalize(Object value) {
    StringBuilder out = new StringBuilder();
    write(out, value, 0);
    return out.toString();
  }

  /** Parses JSON text and returns its canonical form. */
  public static String transform(String json) {
    return canonicalize(Json.parse(json));
  }

  private static void write(StringBuilder out, Object value, int depth) {
    if (depth > MAX_DEPTH) throw new JcsException("value is nested too deeply");
    if (value == null) {
      out.append("null");
    } else if (value instanceof Boolean b) {
      out.append(b ? "true" : "false");
    } else if (value instanceof CharSequence s) {
      quote(out, s.toString());
    } else if (value instanceof Number n) {
      out.append(formatNumber(n.doubleValue()));
    } else if (value instanceof Map<?, ?> map) {
      List<String> keys = new ArrayList<>(map.size());
      for (Object k : map.keySet()) {
        if (!(k instanceof String s)) throw new JcsException("object keys must be strings");
        keys.add(s);
      }
      keys.sort(null); // String.compareTo compares UTF-16 code units, as RFC 8785 requires
      out.append('{');
      boolean first = true;
      for (String k : keys) {
        if (!first) out.append(',');
        first = false;
        quote(out, k);
        out.append(':');
        write(out, map.get(k), depth + 1);
      }
      out.append('}');
    } else if (value instanceof Collection<?> list) {
      out.append('[');
      boolean first = true;
      for (Object item : list) {
        if (!first) out.append(',');
        first = false;
        write(out, item, depth + 1);
      }
      out.append(']');
    } else if (value instanceof Object[] array) {
      write(out, List.of(array), depth);
    } else {
      throw new JcsException("cannot canonicalize a " + value.getClass().getName());
    }
  }

  /**
   * Formats a double the way ECMAScript's Number::toString does: the shortest digits that read
   * back as the same double (the closest such digits when there are several), in plain notation
   * from 1e-6 up to 1e21 and in exponent notation outside it.
   */
  public static String formatNumber(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) throw new JcsException("numbers must be finite");
    if (d == 0) return "0"; // also -0
    StringBuilder out = new StringBuilder();
    if (d < 0) {
      out.append('-');
      d = -d;
    }
    // Shortest round-trip digits. Double.toString is only shortest from Java 19, so search.
    BigDecimal exact = new BigDecimal(d);
    BigDecimal shortest = null;
    for (int p = 1; p <= 17; p++) {
      BigDecimal candidate = exact.round(new MathContext(p, RoundingMode.HALF_EVEN));
      if (candidate.doubleValue() == d) {
        shortest = candidate;
        break;
      }
    }
    if (shortest == null) shortest = exact.round(new MathContext(17, RoundingMode.HALF_EVEN));
    BigDecimal stripped = shortest.stripTrailingZeros();
    String digits = stripped.unscaledValue().abs().toString();
    int k = digits.length();
    int n = k - stripped.scale(); // decimal point position relative to the digits
    if (k <= n && n <= 21) {
      out.append(digits).append("0".repeat(n - k));
    } else if (0 < n && n <= 21) {
      out.append(digits, 0, n).append('.').append(digits, n, k);
    } else if (-6 < n && n <= 0) {
      out.append("0.").append("0".repeat(-n)).append(digits);
    } else {
      out.append(digits.charAt(0));
      if (k > 1) out.append('.').append(digits, 1, k);
      out.append('e').append(n - 1 >= 0 ? "+" : "-").append(Math.abs(n - 1));
    }
    return out.toString();
  }

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  /** Returns a string as a JSON literal, escaped exactly like JavaScript's JSON.stringify. */
  public static String quote(String s) {
    StringBuilder out = new StringBuilder(s.length() + 2);
    quote(out, s);
    return out.toString();
  }

  private static void quote(StringBuilder out, String s) {
    out.append('"');
    int len = s.length();
    for (int i = 0; i < len; i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            unicodeEscape(out, c);
          } else if (Character.isHighSurrogate(c)) {
            if (i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
              out.append(c).append(s.charAt(++i));
            } else {
              unicodeEscape(out, c);
            }
          } else if (Character.isLowSurrogate(c)) {
            unicodeEscape(out, c); // a low surrogate here has no high surrogate before it
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  private static void unicodeEscape(StringBuilder out, char c) {
    out.append("\\u")
        .append(HEX[(c >> 12) & 0xF])
        .append(HEX[(c >> 8) & 0xF])
        .append(HEX[(c >> 4) & 0xF])
        .append(HEX[c & 0xF]);
  }
}
