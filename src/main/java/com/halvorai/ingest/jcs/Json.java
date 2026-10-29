package com.halvorai.ingest.jcs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader with JavaScript's JSON.parse semantics: objects become
 * {@code LinkedHashMap<String, Object>}, arrays {@code ArrayList<Object>}, numbers {@link Double},
 * and a repeated key keeps its last value. Lone surrogate escapes are kept, as Java strings can
 * hold them. Writing is done with {@link Jcs#canonicalize}.
 */
public final class Json {
  private Json() {}

  private static final int MAX_DEPTH = 1000;

  /** Thrown for text that is not valid JSON. */
  public static final class JsonException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
      super(message);
    }
  }

  /** Parses JSON text. */
  public static Object parse(String text) {
    Parser p = new Parser(text);
    p.skipSpace();
    Object value = p.value(0);
    p.skipSpace();
    if (p.i != text.length()) throw p.error("unexpected data after the value");
    return value;
  }

  /** Parses JSON text that must be an object. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseObject(String text) {
    Object v = parse(text);
    if (!(v instanceof Map)) throw new JsonException("expected a JSON object");
    return (Map<String, Object>) v;
  }

  private static final class Parser {
    final String s;
    int i;

    Parser(String s) {
      this.s = s;
    }

    JsonException error(String message) {
      return new JsonException("invalid JSON at offset " + i + ": " + message);
    }

    void skipSpace() {
      while (i < s.length()) {
        char c = s.charAt(i);
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
        else return;
      }
    }

    Object value(int depth) {
      if (depth > MAX_DEPTH) throw error("nested too deeply");
      if (i >= s.length()) throw error("unexpected end of input");
      char c = s.charAt(i);
      switch (c) {
        case '{':
          return object(depth);
        case '[':
          return array(depth);
        case '"':
          return string();
        case 't':
          literal("true");
          return Boolean.TRUE;
        case 'f':
          literal("false");
          return Boolean.FALSE;
        case 'n':
          literal("null");
          return null;
        default:
          if (c == '-' || (c >= '0' && c <= '9')) return number();
          throw error("unexpected character '" + c + "'");
      }
    }

    void literal(String word) {
      if (!s.startsWith(word, i)) throw error("expected " + word);
      i += word.length();
    }

    Map<String, Object> object(int depth) {
      i++;
      Map<String, Object> out = new LinkedHashMap<>();
      skipSpace();
      if (i < s.length() && s.charAt(i) == '}') {
        i++;
        return out;
      }
      while (true) {
        skipSpace();
        if (i >= s.length() || s.charAt(i) != '"') throw error("expected a string key");
        String key = string();
        skipSpace();
        if (i >= s.length() || s.charAt(i) != ':') throw error("expected ':'");
        i++;
        skipSpace();
        Object v = value(depth + 1);
        out.put(key, v); // as in JSON.parse, a repeated key keeps its last value
        skipSpace();
        if (i >= s.length()) throw error("unexpected end of input");
        char c = s.charAt(i++);
        if (c == ',') continue;
        if (c == '}') return out;
        i--;
        throw error("expected ',' or '}'");
      }
    }

    List<Object> array(int depth) {
      i++;
      List<Object> out = new ArrayList<>();
      skipSpace();
      if (i < s.length() && s.charAt(i) == ']') {
        i++;
        return out;
      }
      while (true) {
        skipSpace();
        out.add(value(depth + 1));
        skipSpace();
        if (i >= s.length()) throw error("unexpected end of input");
        char c = s.charAt(i++);
        if (c == ',') continue;
        if (c == ']') return out;
        i--;
        throw error("expected ',' or ']'");
      }
    }

    Double number() {
      int start = i;
      if (s.charAt(i) == '-') i++;
      if (i < s.length() && s.charAt(i) == '0') {
        i++;
      } else if (digits() == 0) {
        throw error("invalid number");
      }
      if (i < s.length() && s.charAt(i) == '.') {
        i++;
        if (digits() == 0) throw error("invalid number");
      }
      if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
        i++;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
        if (digits() == 0) throw error("invalid number");
      }
      return Double.valueOf(s.substring(start, i));
    }

    int digits() {
      int n = 0;
      while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
        i++;
        n++;
      }
      return n;
    }

    String string() {
      i++;
      StringBuilder out = new StringBuilder();
      while (true) {
        if (i >= s.length()) throw error("unterminated string");
        char c = s.charAt(i++);
        if (c == '"') return out.toString();
        if (c < 0x20) throw error("control character in string");
        if (c != '\\') {
          out.append(c);
          continue;
        }
        if (i >= s.length()) throw error("unterminated escape");
        char e = s.charAt(i++);
        switch (e) {
          case '"', '\\', '/' -> out.append(e);
          case 'b' -> out.append('\b');
          case 'f' -> out.append('\f');
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          case 'u' -> {
            if (i + 4 > s.length()) throw error("short \\u escape");
            int code = 0;
            for (int k = 0; k < 4; k++) {
              char h = s.charAt(i + k);
              int digit =
                  h >= '0' && h <= '9' ? h - '0'
                      : h >= 'a' && h <= 'f' ? h - 'a' + 10
                      : h >= 'A' && h <= 'F' ? h - 'A' + 10
                      : -1;
              if (digit < 0) throw error("invalid \\u escape");
              code = code * 16 + digit;
            }
            out.append((char) code);
            i += 4;
          }
          default -> throw error("invalid escape \\" + e);
        }
      }
    }
  }
}
