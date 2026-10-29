package com.halvorai.ingest;

import com.halvorai.ingest.Redactor.RedactPattern;
import com.halvorai.ingest.jcs.Jcs;
import com.halvorai.ingest.jcs.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Queues, signs and delivers Agent Payment Events. Thread-safe.
 *
 * <p>{@link #emit} never throws and never waits for the network or the disk: a background
 * daemon thread batches events for one flush interval, signs them, sends them, retries on
 * network errors, 408, 425, 429 and 5xx, and spools what it cannot deliver. Queued events are
 * flushed for up to 2 seconds when the JVM shuts down normally.
 *
 * <pre>{@code
 * HalvorClient halvor = HalvorClient.builder().build(); // HALVOR_API_KEY, HALVOR_SOURCE_KEY
 * halvor.emit(event);
 * halvor.flush(Duration.ofSeconds(5)); // in short-lived jobs and serverless handlers
 * }</pre>
 */
public final class HalvorClient implements AutoCloseable {
  /** The SDK version, reported in every event's source.sdk. */
  public static final String VERSION = "0.1.0";

  /** The value of source.sdk in every event. */
  public static final String SDK_NAME = "com.halvorai:halvorai-ingest@" + VERSION;

  public static final String PRODUCTION_ENDPOINT = "https://api.halvorai.com";
  public static final String SANDBOX_ENDPOINT = "https://api.sandbox.halvorai.com";

  /** The backoff before each retry; up to 20 % jitter is added. */
  public static final List<Duration> DEFAULT_RETRY_DELAYS = List.of(
      Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
      Duration.ofSeconds(8), Duration.ofSeconds(16), Duration.ofSeconds(32));

  private static final int MAX_BATCH = 100;
  private static final Duration EXIT_FLUSH = Duration.ofSeconds(2);
  private static final Pattern API_KEY = Pattern.compile("^hk_(test|live)_[0-9A-Za-z]{32}$");
  private static final Set<Integer> RETRY_STATUSES = Set.of(408, 425, 429, 500, 502, 503, 504);
  private static final Set<Integer> FATAL_STATUSES = Set.of(400, 401, 403, 404, 405, 409, 410, 415, 422);
  private static final List<String> INSTRUCTION_FIELDS =
      List.of("id", "hash", "summary", "channel", "issued_at", "tool_call_id");

  /** Receives signed envelopes instead of the API. Throwing is treated like a network error. */
  @FunctionalInterface
  public interface Sink {
    void accept(List<Envelope> envelopes) throws Exception;
  }

  private final String apiKey;
  private final String environment;
  private final String endpoint;
  private final Sink sink;
  private final int batchSize;
  private final Duration flushInterval;
  private final List<Duration> retryDelays;
  private final Consumer<HalvorError> onError;
  private final List<RedactPattern> redactPatterns;
  private final HttpClient http;
  private final Spool spool;
  private final Jwk sourceKey;
  private final String sourceId;
  private final Thread exitHook;
  private volatile boolean disabled;
  private boolean configReported;

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition changed = lock.newCondition();
  private final List<Map<String, Object>> queue = new ArrayList<>();
  private int inflight;
  private boolean sendNow;
  private boolean replayRequested;
  private long spoolCooldownUntil;
  private Thread worker;
  private boolean stopping;
  private volatile boolean closed;
  private long emitted, accepted, duplicates, rejected, dropped, droppedInvalid, droppedDisabled, droppedClosed,
      retries, spooled, replayed;

  public static Builder builder() {
    return new Builder();
  }

  /** Where events go for a key: production for live keys, the sandbox otherwise. */
  public static String defaultEndpoint(String apiKey, String environment) {
    if (apiKey != null) return apiKey.startsWith("hk_live_") ? PRODUCTION_ENDPOINT : SANDBOX_ENDPOINT;
    return "live".equals(environment) ? PRODUCTION_ENDPOINT : SANDBOX_ENDPOINT;
  }

  static String eventsUrl(String endpoint) {
    String base = endpoint.replaceAll("/+$", "");
    return base.endsWith("/v1/events") ? base : base + "/v1/events";
  }

  private HalvorClient(Builder b) {
    UnaryOperator<String> env = b.env;
    String key = b.apiKey != null ? b.apiKey : blankToNull(env.apply("HALVOR_API_KEY"));
    this.apiKey = key;
    this.environment = b.environment != null ? b.environment : key != null && key.startsWith("hk_live_") ? "live" : "test";
    String ep = b.endpoint != null ? b.endpoint : blankToNull(env.apply("HALVOR_ENDPOINT"));
    this.endpoint = ep != null ? ep : defaultEndpoint(key, environment);
    this.sink = b.sink;
    this.batchSize = b.batchSize <= 0 || b.batchSize > MAX_BATCH ? MAX_BATCH : b.batchSize;
    this.flushInterval = b.flushInterval == null || b.flushInterval.isZero() || b.flushInterval.isNegative()
        ? Duration.ofSeconds(1) : b.flushInterval;
    this.retryDelays = b.retryDelays == null ? DEFAULT_RETRY_DELAYS : List.copyOf(b.retryDelays);
    this.onError = b.onError;
    this.redactPatterns = b.redactPatterns == null ? List.of() : List.copyOf(b.redactPatterns);
    this.http = b.httpClient != null ? b.httpClient
        : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    Jwk source = b.sourceKey;
    if (source == null) {
      String raw = blankToNull(env.apply("HALVOR_SOURCE_KEY"));
      if (raw != null) {
        try {
          source = Jwk.parse(raw);
        } catch (IllegalArgumentException e) {
          source = null;
        }
      }
    }
    if (source == null || !source.isPrivate()) {
      disable("source key is missing or is not a private Ed25519 JWK (kty OKP, crv Ed25519) with a kid");
      this.sourceKey = null;
      this.sourceId = "src_unknown";
    } else {
      this.sourceKey = source;
      this.sourceId = source.kid();
    }
    if (sink == null && !disabled && (key == null || !API_KEY.matcher(key).matches())) {
      disable("API key is missing or malformed (expected hk_test_… or hk_live_…)");
    }

    this.spool = b.spoolDir == null ? null : new Spool(b.spoolDir,
        b.spoolMaxBytes > 0 ? b.spoolMaxBytes : Spool.DEFAULT_MAX_BYTES,
        b.spoolMaxAge != null && !b.spoolMaxAge.isNegative() && !b.spoolMaxAge.isZero() ? b.spoolMaxAge : Spool.DEFAULT_MAX_AGE);

    Thread hook = null;
    if (b.exitHook) {
      hook = new Thread(() -> flush(EXIT_FLUSH, false), "halvorai-ingest-exit");
      try {
        Runtime.getRuntime().addShutdownHook(hook);
      } catch (IllegalStateException e) {
        hook = null; // already shutting down
      }
    }
    this.exitHook = hook;

    if (!disabled && spool != null && !spool.files().isEmpty()) {
      lock.lock();
      try {
        replayRequested = true; // replay what a previous process left behind
        ensureWorker();
      } finally {
        lock.unlock();
      }
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private void disable(String message) {
    disabled = true;
    if (!configReported) {
      configReported = true;
      notify(HalvorError.of(HalvorError.CONFIG, message));
    }
  }

  /** Passes an error to the error callback. Adapters use it for events they could not map. */
  public void notify(HalvorError error) {
    if (onError == null) return;
    try {
      onError.accept(error);
    } catch (RuntimeException ignored) {
      // a failing callback must never break the caller
    }
  }

  /** A snapshot of the counters. */
  public Stats stats() {
    lock.lock();
    try {
      return new Stats(emitted, queue.size() + inflight, accepted, duplicates, rejected, dropped,
          droppedInvalid, droppedDisabled, droppedClosed, retries, spooled, replayed);
    } finally {
      lock.unlock();
    }
  }

  /** Whether a configuration error disabled the client. */
  public boolean isDisabled() {
    return disabled;
  }

  /** The source id events are signed with. */
  public String sourceId() {
    return sourceId;
  }

  // ------------------------------------------------------------------------- emit

  /**
   * Queues an event and returns its id at once. Never throws, never blocks on I/O. event is an
   * {@link Event} or a {@code Map<String, Object>} in APE shape; agent, owner and instruction
   * come from the current {@link AgentContext} when the event leaves them out.
   */
  public String emit(Object event) {
    return emit(event, null);
  }

  /** Like {@link #emit(Object)}, with an explicit context instead of the current thread's. */
  @SuppressWarnings("unchecked")
  public String emit(Object event, AgentContext context) {
    String id = Ape.newEventId();
    try {
      if (closed) {
        count(() -> { emitted++; droppedClosed++; });
        return id;
      }
      if (disabled) {
        count(() -> { emitted++; droppedDisabled++; });
        return id;
      }
      Map<String, Object> input;
      if (event instanceof ApeObject<?> o) input = o.toMap();
      else if (event instanceof Map<?, ?> m) input = (Map<String, Object>) ApeObject.copy(m);
      else throw new IllegalArgumentException("event must be an Event or a Map");
      if (input.get("id") instanceof String given && !given.isEmpty()) id = given;
      AgentContext ctx = context != null ? context.over(AgentContext.current()) : AgentContext.current();
      Map<String, Object> built = buildEvent(input, ctx, id);
      redactEvent(built);
      List<Ape.Problem> problems = Ape.validateEvent(built);
      if (!problems.isEmpty()) {
        count(() -> { emitted++; droppedInvalid++; });
        notify(new HalvorError(HalvorError.INVALID_EVENT, "event failed schema validation", null, null, id, problems));
        return id;
      }
      lock.lock();
      try {
        queue.add(built);
        emitted++;
        ensureWorker();
        if (queue.size() >= batchSize) {
          sendNow = true;
          changed.signalAll();
        }
      } finally {
        lock.unlock();
      }
      return id;
    } catch (RuntimeException e) {
      count(() -> { emitted++; droppedInvalid++; });
      notify(new HalvorError(HalvorError.INVALID_EVENT, String.valueOf(e.getMessage()), null, null, id, null));
      return id;
    }
  }

  private void count(Runnable update) {
    lock.lock();
    try {
      update.run();
    } finally {
      lock.unlock();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object v) {
    return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
  }

  private Map<String, Object> buildEvent(Map<String, Object> in, AgentContext ctx, String id) {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("id", sourceId);
    source.put("sdk", SDK_NAME);
    source.put("adapter", "custom");
    Map<String, Object> sourceIn = asMap(in.get("source"));
    if (sourceIn != null) source.putAll(sourceIn);
    source.put("id", sourceId);
    source.put("sdk", SDK_NAME);

    Map<String, Object> agent = asMap(in.get("agent"));
    if (agent == null && ctx != null && ctx.agent() != null) agent = ctx.agent().toMap();
    if (agent == null) agent = new LinkedHashMap<>(Map.of("id", "unattributed"));
    Map<String, Object> owner = asMap(in.get("owner"));
    if (owner == null && ctx != null && ctx.owner() != null) owner = ctx.owner().toMap();
    if (owner == null) owner = new LinkedHashMap<>(Map.of("id", "unattributed", "type", "business"));

    Map<String, Object> event = new LinkedHashMap<>();
    event.put("ape_version", Ape.VERSION);
    event.put("id", id);
    event.put("type", in.get("type"));
    event.put("occurred_at", in.get("occurred_at") instanceof String s && !s.isEmpty() ? s : Ape.nowUtc());
    event.put("environment", in.get("environment") instanceof String s && !s.isEmpty() ? s : environment);
    event.put("source", source);
    event.put("agent", agent);
    event.put("owner", owner);
    event.put("payment", in.get("payment"));
    event.put("payee", in.get("payee"));
    Map<String, Object> instruction = asMap(in.get("instruction"));
    if (instruction == null && ctx != null && ctx.instruction() != null) instruction = ctx.instruction().toMap();
    Map<String, Object> builtInstruction = buildInstruction(instruction);
    if (builtInstruction != null) event.put("instruction", builtInstruction);
    for (String key : List.of("instrument", "evidence", "related", "labels")) {
      if (in.get(key) != null) event.put(key, in.get(key));
    }
    if (event.get("evidence") instanceof List<?> evidence) {
      for (Object item : evidence) {
        Map<String, Object> e = asMap(item);
        if (e != null && !e.containsKey("sha256") && e.get("value") != null) e.put("sha256", Ape.evidenceSha256(e.get("value")));
      }
    }
    return event;
  }

  /** An event-level instruction replaces the context one; text is hashed locally and dropped. */
  private static Map<String, Object> buildInstruction(Map<String, Object> raw) {
    if (raw == null || !(raw.get("id") instanceof String id) || id.isEmpty()) return null;
    String hash = raw.get("hash") instanceof String h && !h.isEmpty() ? h : null;
    if (hash == null && raw.get("text") instanceof String text) hash = Ape.hashInstruction(text);
    if (hash == null) return null;
    Map<String, Object> out = new LinkedHashMap<>();
    for (String key : INSTRUCTION_FIELDS) {
      Object value = key.equals("hash") ? hash : raw.get(key);
      if (value instanceof String s && !s.isEmpty()) out.put(key, s);
    }
    return out;
  }

  private void redactEvent(Map<String, Object> event) {
    Map<String, Object> payment = asMap(event.get("payment"));
    if (payment != null) {
      for (String key : List.of("description", "category", "failure_reason")) redactField(payment, key);
    }
    for (String[] path : new String[][] {
        {"agent", "name"}, {"owner", "display_name"}, {"payee", "name"}, {"payee", "website"},
        {"payee", "resource_url"}, {"instruction", "summary"}}) {
      Map<String, Object> parent = asMap(event.get(path[0]));
      if (parent != null) redactField(parent, path[1]);
    }
    Map<String, Object> labels = asMap(event.get("labels"));
    if (labels != null) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<String, Object> e : labels.entrySet()) {
        out.put(Redactor.redactText(e.getKey(), redactPatterns), Redactor.redactDeep(e.getValue(), redactPatterns));
      }
      event.put("labels", out);
    }
    if (event.get("evidence") instanceof List<?> evidence) {
      for (Object item : evidence) {
        Map<String, Object> e = asMap(item);
        if (e == null || !"json".equals(e.get("format")) || e.get("value") == null) continue;
        e.put("value", Redactor.redactDeep(e.get("value"), redactPatterns));
        e.put("sha256", Ape.evidenceSha256(e.get("value")));
      }
    }
  }

  private void redactField(Map<String, Object> obj, String key) {
    if (obj.get(key) instanceof String s) obj.put(key, Redactor.redactText(s, redactPatterns));
  }

  // ------------------------------------------------------------------------- flush and close

  /**
   * Sends everything queued, replays the spool, and waits until all of it is delivered, dropped
   * or spooled, or until the timeout. Returns whether everything was handled. A timeout of null
   * waits for the whole retry schedule (about a minute per batch while the API is down).
   */
  public boolean flush(Duration timeout) {
    return flush(timeout, true);
  }

  private boolean flush(Duration timeout, boolean replaySpool) {
    long deadline = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
    lock.lock();
    try {
      boolean replay = replaySpool && spool != null && !disabled;
      if (worker == null && queue.isEmpty() && !replay) return true;
      sendNow = true;
      if (replay) {
        replayRequested = true;
        spoolCooldownUntil = 0;
      }
      ensureWorker();
      changed.signalAll();
      while (!queue.isEmpty() || inflight > 0 || sendNow || replayRequested) {
        long remaining = deadline == Long.MAX_VALUE ? TimeUnit.SECONDS.toNanos(60) : deadline - System.nanoTime();
        if (remaining <= 0) return false;
        try {
          changed.awaitNanos(remaining);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      return true;
    } finally {
      lock.unlock();
    }
  }

  /** Closes the client, waiting up to 5 seconds for queued events. */
  @Override
  public void close() {
    close(Duration.ofSeconds(5));
  }

  /**
   * Refuses new events, sends what is queued (the spool stays on disk for the next process) and
   * stops the background thread. Returns whether everything queued was handled in time.
   */
  public boolean close(Duration timeout) {
    closed = true;
    boolean drained;
    try {
      drained = flush(timeout, false);
    } finally {
      lock.lock();
      try {
        stopping = true;
        changed.signalAll();
      } finally {
        lock.unlock();
      }
      if (exitHook != null) {
        try {
          Runtime.getRuntime().removeShutdownHook(exitHook);
        } catch (IllegalStateException ignored) {
          // the JVM is shutting down
        }
      }
    }
    return drained;
  }

  // ------------------------------------------------------------------------- worker

  /** Starts the background thread. Call with the lock held. */
  private void ensureWorker() {
    if (worker == null || !worker.isAlive()) {
      stopping = closed; // a flush after close still stops the thread when done
      worker = new Thread(this::run, "halvorai-ingest");
      worker.setDaemon(true);
      worker.start();
    }
  }

  private void run() {
    while (true) {
      boolean replay;
      List<Map<String, Object>> batch;
      lock.lock();
      try {
        if (!sendNow && !replayRequested && queue.size() < batchSize) {
          if (stopping && queue.isEmpty()) {
            worker = null;
            changed.signalAll();
            return;
          }
          // Batch up for one interval; a full batch, flush or close wakes the worker early.
          try {
            changed.awaitNanos(flushInterval.toNanos());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        replay = replayRequested && System.currentTimeMillis() >= spoolCooldownUntil;
        int n = Math.min(queue.size(), batchSize);
        batch = new ArrayList<>(queue.subList(0, n));
        queue.subList(0, n).clear();
        inflight += batch.size();
      } finally {
        lock.unlock();
      }
      try {
        if (replay) replaySpool();
        if (!batch.isEmpty()) {
          Delivery d = deliver(sign(batch));
          park(d.pending, d.reason);
        }
      } catch (RuntimeException e) { // the worker must never die
        notify(HalvorError.of(HalvorError.DELIVERY_FAILED, "worker error: " + e));
      } finally {
        lock.lock();
        try {
          inflight -= batch.size();
          if (replay) replayRequested = false;
          if (queue.isEmpty()) {
            sendNow = false;
            if (!replay && replayRequested && System.currentTimeMillis() < spoolCooldownUntil) {
              replayRequested = false; // the spool is cooling down after an outage; don't hold flush for it
            }
          }
          changed.signalAll();
        } finally {
          lock.unlock();
        }
      }
    }
  }

  private List<Envelope> sign(List<Map<String, Object>> events) {
    List<Envelope> out = new ArrayList<>(events.size());
    for (Map<String, Object> event : events) {
      try {
        out.add(new Envelope(event, Ape.signEvent(event, sourceKey)));
      } catch (RuntimeException e) {
        count(() -> droppedInvalid++);
        notify(new HalvorError(HalvorError.INVALID_EVENT, e.getMessage(), null, null, (String) event.get("id"), null));
      }
    }
    return out;
  }

  // ------------------------------------------------------------------------- delivery

  private record Delivery(List<Envelope> pending, String reason) {
    static final Delivery DONE = new Delivery(List.of(), null);
  }

  private record Outcome(String kind, int status, List<Object> results, HalvorError error) {}

  private Delivery deliver(List<Envelope> envelopes) {
    if (envelopes.isEmpty()) return Delivery.DONE;
    return sink != null ? deliverSink(envelopes) : deliverHttp(envelopes);
  }

  private void sleepBeforeRetry(int attempt) {
    count(() -> retries++);
    long base = retryDelays.get(attempt).toMillis();
    long jitter = (long) (ThreadLocalRandom.current().nextDouble() * 0.2 * base);
    try {
      Thread.sleep(base + jitter);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private Delivery deliverSink(List<Envelope> envelopes) {
    for (int attempt = 0; ; attempt++) {
      try {
        sink.accept(List.copyOf(envelopes));
        count(() -> accepted += envelopes.size());
        return Delivery.DONE;
      } catch (Exception e) {
        if (attempt >= retryDelays.size()) return new Delivery(envelopes, "sink failed: " + e.getMessage());
        sleepBeforeRetry(attempt);
      }
    }
  }

  private Delivery deliverHttp(List<Envelope> envelopes) {
    for (int attempt = 0; ; attempt++) {
      Outcome out = post(envelopes);
      switch (out.kind) {
        case "ok" -> {
          applyResults(envelopes, out.results);
          return Delivery.DONE;
        }
        case "too_large" -> {
          if (envelopes.size() == 1) {
            HalvorError e = out.error;
            dropFatal(envelopes, HalvorError.DELIVERY_FAILED, new HalvorError(e.code(), "event is larger than the API accepts",
                e.serverCode() != null ? e.serverCode() : "payload_too_large", e.requestId(), null, e.details()));
            return Delivery.DONE;
          }
          int mid = (envelopes.size() + 1) / 2;
          Delivery left = deliverHttp(envelopes.subList(0, mid));
          Delivery right = deliverHttp(envelopes.subList(mid, envelopes.size()));
          List<Envelope> pending = new ArrayList<>(left.pending);
          pending.addAll(right.pending);
          return new Delivery(pending, right.reason != null ? right.reason : left.reason);
        }
        case "fatal" -> {
          String code = out.status == 401 || out.status == 403 ? HalvorError.UNAUTHORIZED : HalvorError.DELIVERY_FAILED;
          dropFatal(envelopes, code, out.error);
          return Delivery.DONE;
        }
        default -> {
          if (attempt >= retryDelays.size()) return new Delivery(envelopes, out.error.message());
          sleepBeforeRetry(attempt);
        }
      }
    }
  }

  private Outcome post(List<Envelope> envelopes) {
    List<Object> events = new ArrayList<>(envelopes.size());
    for (Envelope e : envelopes) events.add(Map.of("event", e.event(), "sig", e.sig()));
    String body;
    HttpRequest request;
    try {
      body = Jcs.canonicalize(Map.of("events", events)); // keeps every string and number exactly as signed
      request = HttpRequest.newBuilder(URI.create(eventsUrl(endpoint)))
          .timeout(Duration.ofSeconds(30))
          .header("Authorization", "Bearer " + apiKey)
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .header("User-Agent", "halvorai-ingest-java/" + VERSION)
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();
    } catch (RuntimeException e) {
      return new Outcome("fatal", 0, List.of(), HalvorError.of(null, String.valueOf(e.getMessage())));
    }
    HttpResponse<String> res;
    try {
      res = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      return new Outcome("retry", 0, List.of(), HalvorError.of(null, "network error: " + e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Outcome("retry", 0, List.of(), HalvorError.of(null, "interrupted"));
    }
    int status = res.statusCode();
    if (status == 413) return new Outcome("too_large", status, List.of(), readError(res));
    if (FATAL_STATUSES.contains(status)) return new Outcome("fatal", status, List.of(), readError(res));
    if (RETRY_STATUSES.contains(status) || status < 200 || status >= 300) {
      return new Outcome("retry", status, List.of(), readError(res));
    }
    try {
      Map<String, Object> parsed = Json.parseObject(res.body());
      List<Object> results = parsed.get("results") instanceof List<?> l ? new ArrayList<>(l) : List.of();
      return new Outcome("ok", status, results, null);
    } catch (IllegalArgumentException e) {
      return new Outcome("retry", status, List.of(), HalvorError.of(null, "invalid response body"));
    }
  }

  /** The API's error body, {"error":{"code","message","details","request_id"}}. */
  private static HalvorError readError(HttpResponse<String> res) {
    String text = res.body() == null ? "" : res.body().strip();
    String message = text.isEmpty() ? "HTTP " + res.statusCode() : text.substring(0, Math.min(500, text.length()));
    String serverCode = null;
    String requestId = res.headers().firstValue("request-id").orElse(null);
    Object details = null;
    try {
      Map<String, Object> error = asMap(Json.parseObject(text).get("error"));
      if (error != null) {
        if (error.get("message") instanceof String m && !m.isEmpty()) message = m;
        if (error.get("code") instanceof String c) serverCode = c;
        if (error.get("details") instanceof List<?> d) details = d;
        if (error.get("request_id") instanceof String r && !r.isEmpty()) requestId = r;
      }
    } catch (IllegalArgumentException ignored) {
      // not JSON: keep the text
    }
    return new HalvorError(null, message, serverCode, requestId, null, details);
  }

  /** Counts each event by the status the API gave it. */
  private void applyResults(List<Envelope> envelopes, List<Object> results) {
    Map<String, Map<String, Object>> byId = new HashMap<>();
    for (Object r : results) {
      Map<String, Object> m = asMap(r);
      if (m != null && m.get("id") instanceof String id) byId.put(id, m);
    }
    for (Envelope e : envelopes) {
      String id = (String) e.event().get("id");
      Map<String, Object> result = byId.getOrDefault(id, Map.of());
      String status = result.get("status") instanceof String s ? s : "accepted";
      count(() -> {
        switch (status) {
          case "duplicate" -> duplicates++;
          case "rejected" -> rejected++;
          default -> accepted++;
        }
      });
      if (status.equals("rejected")) {
        String serverCode = null;
        String message = "server rejected event";
        Object details = null;
        Map<String, Object> server = asMap(result.get("error"));
        if (server != null) {
          if (server.get("code") instanceof String c) serverCode = c;
          if (server.get("message") instanceof String m && !m.isEmpty()) message = m;
          details = server.get("details");
        }
        notify(new HalvorError(HalvorError.REJECTED, message, serverCode, null, id, details));
      }
    }
  }

  /** Counts a batch the API will never accept as dropped, once, and keeps a copy in dead/. */
  private void dropFatal(List<Envelope> envelopes, String code, HalvorError error) {
    count(() -> dropped += envelopes.size());
    HalvorError report = new HalvorError(code, error.message() == null || error.message().isEmpty() ? "delivery failed" : error.message(),
        error.serverCode(), error.requestId(), envelopes.size() == 1 ? (String) envelopes.get(0).event().get("id") : null, error.details());
    notify(report);
    if (spool != null) {
      try {
        spool.deadLetter(envelopes);
      } catch (IOException e) {
        notify(HalvorError.of(HalvorError.SPOOL, "dead-letter write failed: " + e.getMessage()));
      }
    }
  }

  /** Spools envelopes that could not be delivered now, or drops them loudly without a spool. */
  private void park(List<Envelope> pending, String reason) {
    if (pending.isEmpty()) return;
    if (spool != null) {
      try {
        Spool.Eviction evicted = spool.write(pending);
        count(() -> { spooled += pending.size(); dropped += evicted.events(); });
        if (evicted.events() > 0) {
          notify(HalvorError.of(HalvorError.SPOOL, "spool limit reached: evicted " + evicted.events()
              + " oldest event(s) in " + evicted.files() + " file(s)"));
        }
        return;
      } catch (IOException e) {
        notify(HalvorError.of(HalvorError.SPOOL, "spool write failed: " + e.getMessage()));
      }
    }
    count(() -> dropped += pending.size());
    notify(HalvorError.of(HalvorError.DELIVERY_FAILED, reason == null ? "delivery failed" : reason));
  }

  /** Sends spooled files oldest first, one at a time. */
  private void replaySpool() {
    if (spool == null || disabled) return;
    for (String name : spool.files()) {
      Spool.SpoolFile file = spool.read(name);
      if (file.corrupt() > 0) notify(HalvorError.of(HalvorError.SPOOL, "skipped " + file.corrupt() + " corrupt spool line(s)"));
      if (file.envelopes().isEmpty()) {
        spool.remove(name);
        continue;
      }
      long droppedBefore = stats().dropped();
      Delivery d = deliver(file.envelopes());
      count(() -> replayed += file.envelopes().size() - d.pending.size() - (dropped - droppedBefore));
      if (d.pending.isEmpty()) {
        spool.remove(name);
        continue;
      }
      // The API is still unavailable: keep only what was not delivered, then back off.
      try {
        spool.rewrite(name, d.pending);
      } catch (IOException e) {
        notify(HalvorError.of(HalvorError.SPOOL, "spool rewrite failed: " + e.getMessage()));
      }
      long cooldown = retryDelays.isEmpty() ? 32_000 : retryDelays.get(retryDelays.size() - 1).toMillis();
      count(() -> spoolCooldownUntil = System.currentTimeMillis() + cooldown);
      return;
    }
  }

  // ------------------------------------------------------------------------- builder

  /** Configures a {@link HalvorClient}. With no options, keys come from the environment. */
  public static final class Builder {
    private String apiKey;
    private Jwk sourceKey;
    private String environment;
    private String endpoint;
    private Sink sink;
    private int batchSize;
    private Duration flushInterval;
    private List<Duration> retryDelays;
    private Path spoolDir;
    private long spoolMaxBytes;
    private Duration spoolMaxAge;
    private Consumer<HalvorError> onError;
    private HttpClient httpClient;
    private List<RedactPattern> redactPatterns;
    private boolean exitHook = true;
    private UnaryOperator<String> env = System::getenv;

    private Builder() {}

    /** An hk_test_ or hk_live_ key. Defaults to HALVOR_API_KEY. */
    public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
    /** The source's private Ed25519 JWK; its kid is the src_ id. Defaults to HALVOR_SOURCE_KEY. */
    public Builder sourceKey(Jwk sourceKey) { this.sourceKey = sourceKey; return this; }
    /** "test" or "live". Defaults to the key's environment, else "test". */
    public Builder environment(String environment) { this.environment = environment; return this; }
    /** The API's base URL. Defaults to HALVOR_ENDPOINT, else sandbox or production by key. */
    public Builder endpoint(String endpoint) { this.endpoint = endpoint; return this; }
    /** Receives signed envelopes instead of the API (offline mode, tests, custom transports). */
    public Builder sink(Sink sink) { this.sink = sink; return this; }
    /** The most events per request (default and maximum 100). */
    public Builder batchSize(int batchSize) { this.batchSize = batchSize; return this; }
    /** How long events wait to be batched (default 1 second). */
    public Builder flushInterval(Duration flushInterval) { this.flushInterval = flushInterval; return this; }
    /** The backoff before each retry (default 1, 2, 4, 8, 16, 32 s); an empty list disables retries. */
    public Builder retryDelays(List<Duration> retryDelays) { this.retryDelays = retryDelays; return this; }
    /** Keeps batches that could not be delivered on disk, to be sent again later. */
    public Builder spoolDir(Path spoolDir) { this.spoolDir = spoolDir; return this; }
    /** The spool's size limit (default 50 MB); the oldest files are evicted first. */
    public Builder spoolMaxBytes(long spoolMaxBytes) { this.spoolMaxBytes = spoolMaxBytes; return this; }
    /** The spool's age limit (default 7 days). */
    public Builder spoolMaxAge(Duration spoolMaxAge) { this.spoolMaxAge = spoolMaxAge; return this; }
    /** Called from the client's thread with every problem. It must not block. */
    public Builder onError(Consumer<HalvorError> onError) { this.onError = onError; return this; }
    /** The HTTP client to send with. */
    public Builder httpClient(HttpClient httpClient) { this.httpClient = httpClient; return this; }
    /** Patterns removed from free-text fields as well as the built-in ones. */
    public Builder redactPatterns(List<RedactPattern> redactPatterns) { this.redactPatterns = redactPatterns; return this; }
    /** Flush for up to 2 seconds when the JVM shuts down (default true). */
    public Builder exitHook(boolean exitHook) { this.exitHook = exitHook; return this; }

    /** For tests: reads environment variables from this function instead of the process. */
    Builder env(UnaryOperator<String> env) { this.env = env; return this; }

    /**
     * Creates the client. It never throws: a missing or malformed key disables the client,
     * reports one config error and counts emitted events as droppedDisabled.
     */
    public HalvorClient build() {
      return new HalvorClient(this);
    }
  }
}
