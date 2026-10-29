package com.halvorai.ingest;

import static com.halvorai.ingest.TestSupport.list;
import static com.halvorai.ingest.TestSupport.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.halvorai.ingest.jcs.Jcs;
import com.halvorai.ingest.jcs.Json;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HalvorClientTest {
  static final String API_KEY = "hk_test_" + "d".repeat(32);

  @TempDir Path dir;

  static Event event() {
    return Event.of(Event.PAYMENT_SETTLED,
        Payment.of("pi_1", "stripe", Amount.minor("100", "USD"), "settled").railRef("payment_intent", "pi_1"),
        Payee.of("merchant").name("Shop"));
  }

  record Reply(int status, String body) {}

  /** A fake API on a local port. handle decides the reply for each batch of event ids. */
  static final class FakeApi implements AutoCloseable {
    final HttpServer server;
    final List<List<String>> batches = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();

    FakeApi(Function<List<String>, Reply> handle) throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        List<String> ids = new ArrayList<>();
        for (Object e : list(Json.parseObject(body).get("events"))) ids.add((String) map(map(e).get("event")).get("id"));
        batches.add(ids);
        auth.add(exchange.getRequestHeaders().getFirst("Authorization") + " " + exchange.getRequestURI().getPath());
        Reply reply = handle == null ? new Reply(202, null) : handle.apply(ids);
        String text = reply.body() != null ? reply.body() : reply.status() == 202 ? acceptAll(ids) : "";
        byte[] out = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(out);
        }
      });
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  static String acceptAll(List<String> ids) {
    List<Object> results = new ArrayList<>();
    for (String id : ids) results.add(Map.of("id", id, "status", "accepted"));
    return Jcs.canonicalize(Map.of("results", results));
  }

  final List<HalvorError> errors = new CopyOnWriteArrayList<>();

  HalvorClient.Builder builder(FakeApi api) {
    return HalvorClient.builder()
        .apiKey(API_KEY)
        .sourceKey(Jwk.generate("src_local"))
        .endpoint(api.url())
        .onError(errors::add)
        .retryDelays(List.of())
        .flushInterval(Duration.ofMillis(20))
        .exitHook(false)
        .env(name -> null);
  }

  List<String> pending() {
    return Spool.list(dir);
  }

  int deadLines() throws IOException {
    int n = 0;
    for (String name : Spool.list(dir.resolve(Spool.DEAD))) {
      n += (int) Files.readString(dir.resolve(Spool.DEAD).resolve(name)).lines().count();
    }
    return n;
  }

  @Test
  void sendsSignedEventsToTheApi() throws Exception {
    try (FakeApi api = new FakeApi(null); HalvorClient c = builder(api).build()) {
      String id = c.emit(event());
      assertTrue(c.flush(Duration.ofSeconds(5)));
      assertEquals(List.of(List.of(id)), api.batches);
      assertEquals("Bearer " + API_KEY + " /v1/events", api.auth.get(0));
      Stats s = c.stats();
      assertEquals(1, s.accepted());
      assertEquals(1, s.emitted());
      assertEquals(0, s.queued());
      assertTrue(errors.isEmpty(), errors.toString());
    }
  }

  @Test
  void emitNeverWaitsForTheNetwork() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    try (FakeApi api = new FakeApi(ids -> {
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new Reply(202, null);
        });
        HalvorClient c = builder(api).batchSize(10).build()) {
      long start = System.nanoTime();
      for (int i = 0; i < 50; i++) c.emit(event());
      assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(500), "emit blocked");
      release.countDown();
      assertTrue(c.flush(Duration.ofSeconds(10)));
      assertEquals(50, c.stats().accepted());
    }
  }

  @Test
  void sendsInTheBackgroundWithoutFlush() throws Exception {
    try (FakeApi api = new FakeApi(null); HalvorClient c = builder(api).build()) {
      c.emit(event());
      long deadline = System.currentTimeMillis() + 2000;
      while (api.batches.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10);
      assertFalse(api.batches.isEmpty(), "nothing sent without flush");
    }
  }

  @Test
  void retries503ThenDelivers() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (FakeApi api = new FakeApi(ids -> calls.incrementAndGet() <= 2 ? new Reply(503, "") : new Reply(202, null));
        HalvorClient c = builder(api).retryDelays(List.of(Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1))).build()) {
      c.emit(event());
      c.flush(Duration.ofSeconds(5));
      Stats s = c.stats();
      assertEquals(2, s.retries());
      assertEquals(1, s.accepted());
      assertEquals(0, s.dropped());
    }
  }

  @Test
  void serverErrorsAreNotCountedAsRejected() throws Exception {
    try (FakeApi api = new FakeApi(ids -> new Reply(503, "")); HalvorClient c = builder(api).build()) {
      c.emit(event());
      c.flush(Duration.ofSeconds(5));
      assertEquals(0, c.stats().rejected());
      assertEquals(1, c.stats().dropped());
      assertEquals(HalvorError.DELIVERY_FAILED, errors.get(errors.size() - 1).code());
    }
  }

  @Test
  void networkErrorsSpoolTheEvents() throws Exception {
    try (FakeApi api = new FakeApi(null)) {
      api.close(); // nothing listens on that port any more
      try (HalvorClient c = builder(api).spoolDir(dir).build()) {
        c.emit(event());
        assertTrue(c.close(Duration.ofSeconds(10)));
        assertEquals(1, c.stats().spooled());
        assertEquals(0, c.stats().dropped());
        assertEquals(1, pending().size());
      }
    }
  }

  @Test
  void spooledEventsAreReplayedByTheNextProcess() throws Exception {
    Jwk key = Jwk.generate("src_local");
    String id;
    try (FakeApi down = new FakeApi(ids -> new Reply(503, "")); HalvorClient c = builder(down).sourceKey(key).spoolDir(dir).build()) {
      id = c.emit(event());
      c.close(Duration.ofSeconds(5));
    }
    assertEquals(1, pending().size());
    try (FakeApi up = new FakeApi(null); HalvorClient c = builder(up).sourceKey(key).spoolDir(dir).build()) {
      assertTrue(c.flush(Duration.ofSeconds(5)));
      assertTrue(up.batches.stream().anyMatch(b -> b.contains(id)));
      assertEquals(List.of(), pending());
      assertEquals(1, c.stats().replayed());
    }
  }

  @Test
  void fatalErrorsCarryTheServerCodeAndAreDeadLettered() throws Exception {
    String body = "{\"error\":{\"code\":\"unknown_source\",\"message\":\"no such source\",\"request_id\":\"req_9\"}}";
    try (FakeApi api = new FakeApi(ids -> new Reply(401, body));
        HalvorClient c = builder(api).spoolDir(dir).retryDelays(List.of(Duration.ofMillis(1))).build()) {
      String id = c.emit(event());
      c.flush(Duration.ofSeconds(5));
      assertEquals(1, api.batches.size(), "fatal errors are not retried");
      HalvorError e = errors.get(errors.size() - 1);
      assertEquals(new HalvorError(HalvorError.UNAUTHORIZED, "no such source", "unknown_source", "req_9", id, null), e);
      assertEquals(1, deadLines());
      assertEquals(List.of(), pending());
    }
  }

  @Test
  void aRejectedSpoolFileDoesNotBlockLaterFiles() throws Exception {
    Jwk key = Jwk.generate("src_local");
    String bad;
    String good;
    try (FakeApi down = new FakeApi(ids -> new Reply(503, "")); HalvorClient c = builder(down).sourceKey(key).spoolDir(dir).build()) {
      bad = c.emit(event());
      c.flush(Duration.ofSeconds(5));
      good = c.emit(event());
      c.close(Duration.ofSeconds(5));
    }
    assertEquals(2, pending().size());
    String badId = bad;
    try (FakeApi up = new FakeApi(ids -> ids.contains(badId) ? new Reply(422, "{\"error\":{\"code\":\"invalid_event\"}}") : new Reply(202, null));
        HalvorClient c = builder(up).sourceKey(key).spoolDir(dir).build()) {
      c.flush(Duration.ofSeconds(5));
      assertTrue(up.batches.contains(List.of(good)));
      assertEquals(List.of(), pending());
      assertEquals(1, c.stats().dropped());
      assertEquals(1, c.stats().replayed());
    }
  }

  @Test
  void aBatchThatIsTooLargeIsSplit() throws Exception {
    try (FakeApi api = new FakeApi(ids -> ids.size() > 2 ? new Reply(413, "") : new Reply(202, null));
        HalvorClient c = builder(api).build()) {
      for (int i = 0; i < 5; i++) c.emit(event());
      c.flush(Duration.ofSeconds(5));
      assertEquals(5, c.stats().accepted());
    }
  }

  @Test
  void aSingleOversizedEventIsDroppedNotSpooled() throws Exception {
    try (FakeApi api = new FakeApi(ids -> new Reply(413, "")); HalvorClient c = builder(api).spoolDir(dir).build()) {
      c.emit(event());
      c.flush(Duration.ofSeconds(5));
      assertEquals(1, c.stats().dropped());
      assertEquals(List.of(), pending());
      assertEquals("payload_too_large", errors.get(errors.size() - 1).serverCode());
    }
  }

  @Test
  void perEventResultsAreCounted() throws Exception {
    Function<List<String>, Reply> handle = ids -> new Reply(202, Jcs.canonicalize(Map.of("results", List.of(
        Map.of("id", ids.get(0), "status", "accepted"),
        Map.of("id", ids.get(1), "status", "duplicate"),
        Map.of("id", ids.get(2), "status", "rejected", "error", Map.of("code", "invalid_signature", "message", "Signature does not match"))))));
    try (FakeApi api = new FakeApi(handle); HalvorClient c = builder(api).batchSize(3).build()) {
      for (int i = 0; i < 3; i++) c.emit(event());
      c.flush(Duration.ofSeconds(5));
      Stats s = c.stats();
      assertEquals(1, s.accepted());
      assertEquals(1, s.duplicates());
      assertEquals(1, s.rejected());
      assertEquals(1, errors.size());
      assertEquals("invalid_signature", errors.get(0).serverCode());
    }
  }

  @Test
  void spoolEvictsTheOldestFilesBeyondItsLimit() throws Exception {
    Spool s = new Spool(dir, 1, Duration.ofHours(1));
    s.write(List.of(new Envelope(Map.of("id", "evt_a"), "s")));
    Spool.Eviction e = s.write(List.of(new Envelope(Map.of("id", "evt_b"), "s")));
    assertTrue(e.files() >= 1 && e.events() >= 1, e.toString());
  }

  @Test
  void badConfigDisablesTheClientOnce() {
    Jwk good = Jwk.generate("src_local");
    Map<String, HalvorClient.Builder> cases = Map.of(
        "public key only", HalvorClient.builder().apiKey(API_KEY).sourceKey(good.toPublic()),
        "no kid", HalvorClient.builder().apiKey(API_KEY).sourceKey(new Jwk("OKP", "Ed25519", good.x(), good.d(), null)),
        "wrong curve", HalvorClient.builder().apiKey(API_KEY).sourceKey(new Jwk("EC", "P-256", good.x(), good.d(), "k")),
        "bad API key", HalvorClient.builder().apiKey("sk_live_nope").sourceKey(good),
        "nothing set", HalvorClient.builder());
    for (Map.Entry<String, HalvorClient.Builder> e : cases.entrySet()) {
      List<HalvorError> seen = new ArrayList<>();
      HalvorClient c = e.getValue().onError(seen::add).exitHook(false).env(name -> null).build();
      c.emit(event());
      c.emit(event());
      assertEquals(1, seen.size(), e.getKey());
      assertEquals(HalvorError.CONFIG, seen.get(0).code(), e.getKey());
      assertEquals(2, c.stats().droppedDisabled(), e.getKey());
      assertTrue(c.isDisabled());
    }
  }

  @Test
  void readsKeysFromTheEnvironment() throws Exception {
    Jwk key = Jwk.generate("src_env");
    try (FakeApi api = new FakeApi(null)) {
      Map<String, String> env = Map.of("HALVOR_API_KEY", API_KEY, "HALVOR_SOURCE_KEY", key.toJson(), "HALVOR_ENDPOINT", api.url());
      try (HalvorClient c = HalvorClient.builder().env(env::get).exitHook(false).onError(errors::add).build()) {
        assertFalse(c.isDisabled(), errors.toString());
        assertEquals("src_env", c.sourceId());
        c.emit(event());
        assertTrue(c.flush(Duration.ofSeconds(5)));
        assertEquals(1, api.batches.size());
      }
    }
    assertEquals(HalvorClient.SANDBOX_ENDPOINT, HalvorClient.defaultEndpoint(API_KEY, "test"));
    assertEquals(HalvorClient.PRODUCTION_ENDPOINT, HalvorClient.defaultEndpoint("hk_live_" + "a".repeat(32), "test"));
    assertEquals("https://x/v1/events", HalvorClient.eventsUrl("https://x/"));
    assertEquals("https://x/v1/events", HalvorClient.eventsUrl("https://x/v1/events"));
  }

  @Test
  void invalidEventsAreDroppedAndReported() throws Exception {
    try (FakeApi api = new FakeApi(null); HalvorClient c = builder(api).build()) {
      Event bad = Event.of(Event.PAYMENT_SETTLED,
          Payment.of("pi_1", "stripe", Amount.minor("100", "usd"), "settled"), Payee.of("merchant").name("Shop"));
      c.emit(bad);
      c.emit(Map.of("type", "nope"));
      c.emit("not an event");
      c.flush(Duration.ofSeconds(1));
      assertEquals(List.of(), api.batches);
      assertEquals(3, c.stats().droppedInvalid());
      assertEquals(3, errors.size());
      assertEquals(HalvorError.INVALID_EVENT, errors.get(0).code());
    }
  }

  @Test
  void closeRefusesNewEvents() throws Exception {
    try (FakeApi api = new FakeApi(null)) {
      HalvorClient c = builder(api).build();
      c.emit(event());
      assertTrue(c.close(Duration.ofSeconds(5)));
      c.emit(event());
      assertEquals(1, api.batches.size());
      assertEquals(1, c.stats().droppedClosed());
    }
  }

  @Test
  void countersAreExactUnderConcurrentEmits() throws Exception {
    AtomicInteger received = new AtomicInteger();
    HalvorClient c = HalvorClient.builder().sourceKey(Jwk.generate("src_local")).exitHook(false).env(n -> null)
        .sink(envelopes -> received.addAndGet(envelopes.size())).build();
    List<Thread> threads = new ArrayList<>();
    for (int t = 0; t < 4; t++) {
      Thread thread = new Thread(() -> {
        for (int i = 0; i < 200; i++) c.emit(event());
      });
      threads.add(thread);
      thread.start();
    }
    for (Thread t : threads) t.join();
    assertTrue(c.flush(Duration.ofSeconds(10)));
    assertEquals(800, c.stats().emitted());
    assertEquals(800, c.stats().accepted());
    assertEquals(800, received.get());
    c.close();
  }

  @Test
  void sinkErrorsAreRetriedThenSpooled() {
    AtomicInteger calls = new AtomicInteger();
    HalvorClient c = HalvorClient.builder().sourceKey(Jwk.generate("src_local")).exitHook(false).env(n -> null)
        .spoolDir(dir).retryDelays(List.of(Duration.ofMillis(1)))
        .sink(envelopes -> {
          calls.incrementAndGet();
          throw new IOException("down");
        }).build();
    c.emit(event());
    c.close(Duration.ofSeconds(5));
    assertEquals(2, calls.get());
    assertEquals(1, c.stats().spooled());
  }

  @Test
  void flushHonoursItsTimeout() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    try (FakeApi api = new FakeApi(ids -> {
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new Reply(202, null);
        });
        HalvorClient c = builder(api).build()) {
      c.emit(event());
      assertFalse(c.flush(Duration.ofMillis(100)));
      release.countDown();
    }
  }

  @Test
  void eventsAreBuiltRedactedAndSigned() throws Exception {
    List<Envelope> got = Collections.synchronizedList(new ArrayList<>());
    Jwk key = Jwk.generate("src_local");
    HalvorClient c = HalvorClient.builder().sourceKey(key).exitHook(false).env(n -> null).sink(got::addAll).build();
    Event e = Event.of(Event.PAYMENT_SETTLED,
            Payment.of("pi_1", "stripe", Amount.minor("100", "USD"), "settled").railRef("payment_intent", "pi_1")
                .description("card 4242 4242 4242 4242 used"),
            Payee.of("merchant").name("Shop").website("https://shop.example/?card=4242424242424242"))
        .label("4242 4242 4242 4242", "x")
        .label("team", "sk_live_abcdef123")
        .evidence(Evidence.of("stripe.object", "json", Map.of("note", "4242424242424242")));
    AgentContext outer = AgentContext.of(
        Agent.of("agt_1").name("Research agent").framework("langgraph"),
        Owner.of("own_1", "person").country("US"),
        Instruction.of("ctx", "from context").channel("chat"));
    try (var a = outer.open(); var b = AgentContext.of(Agent.of("agt_inner"), null, null).open()) {
      c.emit(e);
      c.emit(Event.of(Event.PAYMENT_SETTLED, Payment.of("pi_2", "stripe", Amount.minor("1", "USD"), "settled"),
          Payee.of("merchant").name("Shop")).instruction(Instruction.of("evt_level", "pay")));
    }
    assertNull(AgentContext.current());
    c.close(Duration.ofSeconds(5));
    assertEquals(2, got.size());
    Map<String, Object> first = got.get(0).event();
    String text = Jcs.canonicalize(first);
    assertFalse(text.contains("4242 4242") || text.contains("4242424242424242") || text.contains("sk_live_"), text);
    assertEquals(List.of(), Ape.validateEvent(first));
    assertTrue(got.get(0).verify(key.toPublic()));
    assertEquals("agt_inner", map(first.get("agent")).get("id"));
    assertEquals("US", map(first.get("owner")).get("country"));
    Map<String, Object> instruction = map(first.get("instruction"));
    assertEquals(Ape.hashInstruction("from context"), instruction.get("hash"));
    assertNull(instruction.get("text"));
    assertEquals("chat", instruction.get("channel"));
    Map<String, Object> second = map(got.get(1).event().get("instruction"));
    assertEquals(Map.of("id", "evt_level", "hash", Ape.hashInstruction("pay")), second);
    Map<String, Object> source = map(first.get("source"));
    assertEquals(HalvorClient.SDK_NAME, source.get("sdk"));
    assertEquals("src_local", source.get("id"));
    assertEquals("test", first.get("environment"));
  }

  @Test
  void contextCrossesThreadsOnlyWhenWrapped() throws Exception {
    List<Envelope> got = Collections.synchronizedList(new ArrayList<>());
    HalvorClient c = HalvorClient.builder().sourceKey(Jwk.generate("src_local")).exitHook(false).env(n -> null).sink(got::addAll).build();
    Consumer<String> emitOnNewThread = label -> {
      Thread t = new Thread(label.equals("wrapped") ? AgentContext.wrap(() -> c.emit(event())) : (Runnable) () -> c.emit(event()));
      t.start();
      try {
        t.join();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
    };
    try (var s = AgentContext.of(Agent.of("agt_ctx"), null, null).open()) {
      emitOnNewThread.accept("wrapped");
      emitOnNewThread.accept("plain");
    }
    c.close(Duration.ofSeconds(5));
    assertEquals("agt_ctx", map(got.get(0).event().get("agent")).get("id"));
    assertEquals("unattributed", map(got.get(1).event().get("agent")).get("id"));
  }
}
