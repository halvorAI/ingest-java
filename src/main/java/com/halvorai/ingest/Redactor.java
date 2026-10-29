package com.halvorai.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Removes card numbers, CVCs, API and secret keys and private keys from free text. */
public final class Redactor {
  private Redactor() {}

  private static final Pattern SECRET_KEY =
      Pattern.compile("\\b(?:sk_live_|sk_test_|rk_live_|whsec_|hk_live_|hk_test_)[0-9A-Za-z]+\\b");
  private static final Pattern PEM_PRIVATE =
      Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----");
  private static final Pattern JWK_D = Pattern.compile("\"d\"\\s*:\\s*\"[^\"]*\"");
  private static final Pattern CVC =
      Pattern.compile("\\b(cvc|cvv2?|security code)(\"\\s*:\\s*\"|\\s*[:=]\\s*|\\s+)(\\d{3,4})(\")?", Pattern.CASE_INSENSITIVE);
  private static final Pattern PAN_RUN = Pattern.compile("[0-9](?:[ -]?[0-9])*");
  private static final int MAX_DEPTH = 64;

  /**
   * An extra pattern to remove. Matches become "[redacted:label]", or "[redacted]" without a
   * label.
   */
  public record RedactPattern(Pattern pattern, String label) {}

  private static boolean luhnValid(String digits) {
    int sum = 0;
    boolean dbl = false;
    for (int i = digits.length() - 1; i >= 0; i--) {
      int d = digits.charAt(i) - '0';
      if (dbl) {
        d *= 2;
        if (d > 9) d -= 9;
      }
      sum += d;
      dbl = !dbl;
    }
    return sum % 10 == 0;
  }

  private static boolean isPan(String run) {
    String digits = run.replace(" ", "").replace("-", "");
    return digits.length() >= 13 && digits.length() <= 19 && luhnValid(digits);
  }

  /**
   * Whether text holds a card number: a Luhn-valid run of 13 to 19 digits, with single spaces or
   * dashes allowed between digits. This is the APE card-number rule.
   */
  public static boolean containsPan(String text) {
    Matcher m = PAN_RUN.matcher(text);
    while (m.find()) {
      if (isPan(m.group())) return true;
    }
    return false;
  }

  /** Redacts free text. */
  public static String redactText(String text, List<RedactPattern> extra) {
    String out = PEM_PRIVATE.matcher(text).replaceAll("[redacted:private-key]");
    out = SECRET_KEY.matcher(out).replaceAll("[redacted:secret-key]");
    out = JWK_D.matcher(out).replaceAll(Matcher.quoteReplacement("\"d\":\"[redacted:private-key]\""));
    out = PAN_RUN.matcher(out).replaceAll(m -> Matcher.quoteReplacement(isPan(m.group()) ? "[redacted:pan]" : m.group()));
    out = CVC.matcher(out).replaceAll(m -> Matcher.quoteReplacement(
        m.group(1) + m.group(2) + "[redacted:cvc]" + (m.group(4) == null ? "" : m.group(4))));
    if (extra != null) {
      for (RedactPattern p : extra) {
        if (p == null || p.pattern() == null) continue;
        String label = p.label() == null || p.label().isEmpty() ? "[redacted]" : "[redacted:" + p.label() + "]";
        out = p.pattern().matcher(out).replaceAll(Matcher.quoteReplacement(label));
      }
    }
    return out;
  }

  /** Redacts free text with the built-in patterns. */
  public static String redactText(String text) {
    return redactText(text, List.of());
  }

  /**
   * Returns a copy of a decoded JSON value with every string redacted and the private part of
   * any JWK replaced.
   */
  public static Object redactDeep(Object value, List<RedactPattern> extra) {
    return redactDeep(value, extra, 0);
  }

  private static Object redactDeep(Object value, List<RedactPattern> extra, int depth) {
    if (depth > MAX_DEPTH) return null;
    if (value instanceof String s) return redactText(s, extra);
    if (value instanceof List<?> list) {
      List<Object> out = new ArrayList<>(list.size());
      for (Object item : list) out.add(redactDeep(item, extra, depth + 1));
      return out;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : map.entrySet()) out.put(String.valueOf(e.getKey()), redactDeep(e.getValue(), extra, depth + 1));
      if (map.get("kty") instanceof String && map.get("d") instanceof String) out.put("d", "[redacted:private-key]");
      return out;
    }
    return value;
  }
}
