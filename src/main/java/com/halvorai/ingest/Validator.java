package com.halvorai.ingest;

import com.halvorai.ingest.Ape.Problem;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** APE v0.1 validation, the same rules as the Node.js, Python and Go SDKs. */
final class Validator {
  private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
  private static final Pattern CAIP2 = Pattern.compile("^[-a-z0-9]{3,8}:[-_a-zA-Z0-9]{1,32}$");
  private static final Pattern SHA256 = Pattern.compile("^sha256:[0-9a-f]{64}$");
  private static final Pattern AMOUNT = Pattern.compile("^(0|[1-9][0-9]*)$");
  private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
  private static final Pattern FOUR = Pattern.compile("^[0-9]{4}$");
  private static final Pattern DATE_TIME =
      Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$");
  private static final Pattern URL = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://\\S+$");

  private static final List<String> SOURCE_ADAPTERS =
      List.of("stripe", "stripe-issuing", "x402-client", "x402-server", "mpp-server", "mcp", "manual", "custom");
  private static final List<String> EVENT_TYPES = List.of(
      "payment.requested", "payment.authorized", "payment.settled", "payment.failed", "payment.refunded", "payment.disputed");
  private static final List<String> STATUSES =
      List.of("requested", "authorized", "settled", "failed", "refunded", "disputed");
  private static final List<String> EVIDENCE_KINDS = List.of(
      "ap2.payment_mandate", "ap2.checkout_mandate", "vi.chain", "tap.signature", "x402.payment_payload",
      "x402.settlement", "mpp.receipt", "stripe.object", "agentic_token.metadata", "kya.attestation");
  private static final Map<String, List<String>> RAIL_REF_KEYS = Map.of(
      "stripe", List.of("account", "payment_intent", "charge", "refund", "dispute", "shared_payment_token"),
      "card", List.of("authorization_id", "transaction_id", "network_transaction_id", "processor"),
      "x402", List.of("scheme", "network", "nonce", "tx_hash", "facilitator", "payment_flow"),
      "mpp", List.of("method", "challenge_id", "payment_intent", "tx_hash"),
      "stablecoin", List.of("network", "tx_hash", "log_index"),
      "bank_transfer", List.of("scheme", "end_to_end_id", "pisp"));

  private final List<Problem> problems = new ArrayList<>();
  private final List<String[]> free = new ArrayList<>(); // {path, text}

  private Validator() {}

  static List<Problem> validate(Map<String, Object> event) {
    Validator v = new Validator();
    v.event(event);
    return List.copyOf(v.problems);
  }

  private void add(String path, String message) {
    problems.add(new Problem(path, message));
  }

  private static String join(String path, String key) {
    return path.isEmpty() ? key : path + "." + key;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> object(String path, Object value, List<String> required, List<String> optional) {
    if (!(value instanceof Map<?, ?> raw)) {
      add(path, "expected an object");
      return null;
    }
    Map<String, Object> obj = (Map<String, Object>) raw;
    Set<String> allowed = new HashSet<>(required);
    allowed.addAll(optional);
    for (String k : required) {
      if (!obj.containsKey(k)) add(join(path, k), "is required");
    }
    for (Object k : new TreeSet<>(obj.keySet())) {
      String key = String.valueOf(k);
      if (!allowed.contains(key)) add(join(path, key), "is not an APE v0.1 field");
      else if (obj.get(key) == null) add(join(path, key), "null is not allowed; leave the field out");
    }
    return obj;
  }

  private static Object present(Map<String, Object> obj, String key) {
    return obj.get(key);
  }

  private String str(Map<String, Object> obj, String path, String key, int max, Predicate<String> check, String what) {
    Object value = obj.get(key);
    if (value == null) return null;
    String p = join(path, key);
    if (!(value instanceof String s)) {
      add(p, "expected a string");
      return null;
    }
    if (max > 0 && s.length() > max) {
      add(p, "must be at most " + max + " characters");
      return null;
    }
    if (check != null && !check.test(s)) {
      add(p, "must be " + what);
      return null;
    }
    return s;
  }

  private String maxStr(Map<String, Object> obj, String path, String key, int max) {
    return str(obj, path, key, max, null, "");
  }

  private String pattern(Map<String, Object> obj, String path, String key, Pattern re, String what) {
    return str(obj, path, key, 0, s -> re.matcher(s).matches(), what);
  }

  private String oneOf(Map<String, Object> obj, String path, String key, List<String> values) {
    return str(obj, path, key, 0, values::contains, "one of " + String.join(", ", values));
  }

  static boolean isDateTime(String s) {
    Matcher m = DATE_TIME.matcher(s);
    if (!m.matches()) return false;
    String fraction = m.group(1);
    String normalized = s;
    if (fraction != null && fraction.length() > 10) {
      normalized = s.replace(fraction, fraction.substring(0, 10)); // the parser takes up to 9 digits
    }
    try {
      OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private void dateTime(Map<String, Object> obj, String path, String key) {
    str(obj, path, key, 0, Validator::isDateTime, "an RFC 3339 date-time with an offset");
  }

  private String url(Map<String, Object> obj, String path, String key) {
    return str(obj, path, key, 0, s -> URL.matcher(s).matches(), "a URL");
  }

  private static boolean isInteger(Object value) {
    if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) return true;
    if (value instanceof java.math.BigInteger) return true;
    if (value instanceof Number n) {
      double d = n.doubleValue();
      return !Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d);
    }
    return false;
  }

  private void freeText(String path, String text) {
    if (text != null) free.add(new String[] {path, text});
  }

  private void event(Map<String, Object> event) {
    Map<String, Object> obj = object("", event,
        List.of("ape_version", "id", "type", "occurred_at", "environment", "source", "agent", "owner", "payment", "payee"),
        List.of("instruction", "instrument", "evidence", "related", "labels"));
    if (obj == null) return;
    str(obj, "", "ape_version", 0, Ape.VERSION::equals, "\"0.1\"");
    String id = maxStr(obj, "", "id", 128);
    if (id != null && id.length() < 8) add("id", "must be at least 8 characters");
    oneOf(obj, "", "type", EVENT_TYPES);
    dateTime(obj, "", "occurred_at");
    oneOf(obj, "", "environment", List.of("test", "live"));

    if (present(obj, "source") != null) {
      Map<String, Object> s = object("source", obj.get("source"), List.of("id", "sdk", "adapter"), List.of());
      if (s != null) {
        maxStr(s, "source", "id", 64);
        maxStr(s, "source", "sdk", 64);
        oneOf(s, "source", "adapter", SOURCE_ADAPTERS);
      }
    }
    if (present(obj, "agent") != null) {
      Map<String, Object> a = object("agent", obj.get("agent"), List.of("id"),
          List.of("name", "framework", "version", "key_thumbprint", "kya"));
      if (a != null) {
        maxStr(a, "agent", "id", 128);
        freeText("agent.name", maxStr(a, "agent", "name", 128));
        maxStr(a, "agent", "framework", 64);
        maxStr(a, "agent", "version", 64);
        maxStr(a, "agent", "key_thumbprint", 64);
        if (present(a, "kya") != null) {
          Map<String, Object> k = object("agent.kya", a.get("kya"), List.of("provider", "subject"), List.of("assurance", "verified_at"));
          if (k != null) {
            maxStr(k, "agent.kya", "provider", 64);
            maxStr(k, "agent.kya", "subject", 256);
            maxStr(k, "agent.kya", "assurance", 64);
            dateTime(k, "agent.kya", "verified_at");
          }
        }
      }
    }
    if (present(obj, "owner") != null) {
      Map<String, Object> o = object("owner", obj.get("owner"), List.of("id", "type"), List.of("display_name", "country", "kyc_ref"));
      if (o != null) {
        maxStr(o, "owner", "id", 128);
        oneOf(o, "owner", "type", List.of("person", "business"));
        freeText("owner.display_name", maxStr(o, "owner", "display_name", 128));
        pattern(o, "owner", "country", COUNTRY, "an ISO 3166-1 alpha-2 code");
        maxStr(o, "owner", "kyc_ref", 128);
      }
    }
    if (present(obj, "instruction") != null) {
      Map<String, Object> in = object("instruction", obj.get("instruction"), List.of("id", "hash"),
          List.of("summary", "channel", "issued_at", "tool_call_id"));
      if (in != null) {
        maxStr(in, "instruction", "id", 128);
        pattern(in, "instruction", "hash", SHA256, "sha256: and 64 lowercase hex digits");
        freeText("instruction.summary", maxStr(in, "instruction", "summary", 500));
        oneOf(in, "instruction", "channel", List.of("chat", "api", "schedule", "email", "agent", "other"));
        dateTime(in, "instruction", "issued_at");
        maxStr(in, "instruction", "tool_call_id", 128);
      }
    }
    if (present(obj, "payment") != null) payment(obj.get("payment"));
    if (present(obj, "payee") != null) payee(obj.get("payee"));
    if (present(obj, "instrument") != null) {
      Map<String, Object> in = object("instrument", obj.get("instrument"), List.of("type"),
          List.of("network", "last4", "par", "token_ref", "wallet", "issuer"));
      if (in != null) {
        oneOf(in, "instrument", "type", List.of("card", "agentic_token", "stripe_spt", "wallet", "bank_account"));
        maxStr(in, "instrument", "network", 32);
        pattern(in, "instrument", "last4", FOUR, "4 digits");
        maxStr(in, "instrument", "par", 64);
        maxStr(in, "instrument", "token_ref", 128);
        if (present(in, "wallet") != null) wallet("instrument.wallet", in.get("wallet"));
        maxStr(in, "instrument", "issuer", 128);
      }
    }
    if (present(obj, "evidence") != null) evidence(obj.get("evidence"));
    if (present(obj, "related") != null) {
      Map<String, Object> r = object("related", obj.get("related"), List.of(),
          List.of("previous_event_id", "corrects_event_id", "session_id"));
      if (r != null) {
        maxStr(r, "related", "previous_event_id", 128);
        maxStr(r, "related", "corrects_event_id", 128);
        maxStr(r, "related", "session_id", 128);
      }
    }
    if (present(obj, "labels") != null) labels(obj.get("labels"));
    for (String[] f : free) {
      if (Redactor.containsPan(f[1])) add(f[0], Problem.PAN_DETECTED);
    }
  }

  private void wallet(String path, Object value) {
    Map<String, Object> w = object(path, value, List.of("network", "address"), List.of());
    if (w != null) {
      pattern(w, path, "network", CAIP2, "a CAIP-2 chain id");
      maxStr(w, path, "address", 128);
    }
  }

  private void payment(Object value) {
    String rail = value instanceof Map<?, ?> m && m.get("rail") instanceof String s ? s : null;
    List<String> refKeys = rail == null ? null : RAIL_REF_KEYS.get(rail);
    Map<String, Object> p = object("payment", value, List.of("payment_id", "rail", "amount", "status", "rail_ref"),
        List.of("failure_reason", "description", "category"));
    if (p == null) return;
    maxStr(p, "payment", "payment_id", 256);
    if (p.get("rail") != null && refKeys == null) {
      add("payment.rail", "must be one of stripe, card, x402, mpp, stablecoin, bank_transfer");
    }
    if (p.get("amount") != null) amount(p.get("amount"));
    String status = oneOf(p, "payment", "status", STATUSES);
    freeText("payment.failure_reason", maxStr(p, "payment", "failure_reason", 128));
    if ("failed".equals(status) && !p.containsKey("failure_reason")) {
      add("payment.failure_reason", "failure_reason is required when status is failed");
    }
    freeText("payment.description", maxStr(p, "payment", "description", 256));
    freeText("payment.category", maxStr(p, "payment", "category", 64));
    if (p.get("rail_ref") != null && refKeys != null) {
      Map<String, Object> r = object("payment.rail_ref", p.get("rail_ref"), List.of(), refKeys);
      if (r != null) {
        for (Map.Entry<String, Object> e : r.entrySet()) {
          Object item = e.getValue();
          if (item != null && !(item instanceof String) && !isInteger(item)) {
            add("payment.rail_ref." + e.getKey(), "must be a string or an integer");
          }
        }
      }
    }
  }

  private void asset(String path, Object value) {
    Map<String, Object> a = object(path, value, List.of("network", "address"), List.of("symbol", "decimals"));
    if (a == null) return;
    pattern(a, path, "network", CAIP2, "a CAIP-2 chain id");
    maxStr(a, path, "address", 128);
    maxStr(a, path, "symbol", 16);
    Object d = a.get("decimals");
    if (d != null && (!isInteger(d) || ((Number) d).doubleValue() < 0 || ((Number) d).doubleValue() > 36)) {
      add(path + ".decimals", "must be an integer from 0 to 36");
    }
  }

  private void amount(Object value) {
    String unit = value instanceof Map<?, ?> m && m.get("unit") instanceof String s ? s : null;
    Map<String, Object> a;
    if ("minor".equals(unit)) {
      a = object("payment.amount", value, List.of("value", "unit", "currency"), List.of("asset"));
    } else if ("atomic".equals(unit)) {
      a = object("payment.amount", value, List.of("value", "unit", "asset"), List.of("currency"));
    } else {
      if (value instanceof Map) add("payment.amount.unit", "must be \"minor\" or \"atomic\"");
      else add("payment.amount", "expected an object");
      return;
    }
    if (a == null) return;
    pattern(a, "payment.amount", "value", AMOUNT, "a non-negative integer string");
    pattern(a, "payment.amount", "currency", CURRENCY, "an ISO 4217 code");
    if (a.get("asset") != null) asset("payment.amount.asset", a.get("asset"));
  }

  private void payee(Object value) {
    Map<String, Object> p = object("payee", value, List.of("type"),
        List.of("name", "id", "website", "country", "mcc", "wallet", "resource_url"));
    if (p == null) return;
    oneOf(p, "payee", "type", List.of("merchant", "api_provider", "wallet", "business", "person"));
    freeText("payee.name", maxStr(p, "payee", "name", 256));
    maxStr(p, "payee", "id", 128);
    freeText("payee.website", url(p, "payee", "website"));
    pattern(p, "payee", "country", COUNTRY, "an ISO 3166-1 alpha-2 code");
    pattern(p, "payee", "mcc", FOUR, "4 digits");
    if (p.get("wallet") != null) wallet("payee.wallet", p.get("wallet"));
    freeText("payee.resource_url", url(p, "payee", "resource_url"));
    if (p.get("name") == null && p.get("wallet") == null && p.get("id") == null) {
      add("payee", "payee needs at least one of name, wallet or id");
    }
  }

  private void evidence(Object value) {
    if (!(value instanceof List<?> items)) {
      add("evidence", "expected an array");
      return;
    }
    if (items.size() > 16) add("evidence", "at most 16 items");
    for (int i = 0; i < items.size(); i++) {
      String path = "evidence." + i;
      Map<String, Object> e = object(path, items.get(i), List.of("kind", "format", "sha256"), List.of("value", "uri", "captured_at"));
      if (e == null) continue;
      oneOf(e, path, "kind", EVIDENCE_KINDS);
      oneOf(e, path, "format", List.of("sd-jwt", "kb-sd-jwt", "jws", "http-signature", "json"));
      Object v = e.get("value");
      boolean hasValue = v != null;
      if (hasValue && !(v instanceof String) && !(v instanceof Map)) add(path + ".value", "must be a string or an object");
      boolean hasUri = e.get("uri") != null;
      url(e, path, "uri");
      pattern(e, path, "sha256", SHA256, "sha256: and 64 lowercase hex digits");
      dateTime(e, path, "captured_at");
      if (hasValue == hasUri) add(path, "evidence needs exactly one of value or uri");
    }
  }

  private void labels(Object value) {
    if (!(value instanceof Map<?, ?> labels)) {
      add("labels", "expected an object");
      return;
    }
    if (labels.size() > 20) add("labels", "at most 20 labels");
    for (Object rawKey : new TreeSet<>(labels.keySet())) {
      String k = String.valueOf(rawKey);
      String path = "labels." + k;
      if (k.length() > 64) add(path, "label keys must be at most 64 characters");
      freeText(path, k);
      Object v = labels.get(rawKey);
      if (v == null) add(path, "null is not allowed; leave the field out");
      else if (!(v instanceof String s)) add(path, "label values must be strings");
      else if (s.length() > 256) add(path, "label values must be at most 256 characters");
      else freeText(path, s);
    }
  }
}
