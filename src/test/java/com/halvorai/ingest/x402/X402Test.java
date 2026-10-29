package com.halvorai.ingest.x402;

import static com.halvorai.ingest.TestSupport.json;
import static com.halvorai.ingest.TestSupport.list;
import static com.halvorai.ingest.TestSupport.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.halvorai.ingest.Agent;
import com.halvorai.ingest.AgentContext;
import com.halvorai.ingest.Ape;
import com.halvorai.ingest.Envelope;
import com.halvorai.ingest.HalvorClient;
import com.halvorai.ingest.HalvorError;
import com.halvorai.ingest.Jwk;
import com.halvorai.ingest.Owner;
import com.halvorai.ingest.Payee;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The fixtures are the Node.js SDK's (src/x402/fixtures), shared with the Python and Go SDKs. */
class X402Test {
  final List<Envelope> got = Collections.synchronizedList(new ArrayList<>());
  final List<HalvorError> errors = Collections.synchronizedList(new ArrayList<>());
  final HalvorClient client = HalvorClient.builder().sourceKey(Jwk.generate("src_local")).exitHook(false)
      .sink(got::addAll).onError(errors::add).build();

  List<Map<String, Object>> events() {
    client.close(Duration.ofSeconds(5));
    assertEquals(List.of(), errors);
    List<Map<String, Object>> out = new ArrayList<>();
    for (Envelope e : got) {
      assertEquals(List.of(), Ape.validateEvent(e.event()));
      out.add(e.event());
    }
    return out;
  }

  static Map<String, Object> fixture(String name) {
    return json("x402/" + name);
  }

  @Test
  void matchesTheNodeSdkForTheSharedFixtures() {
    Map<String, Object> required = fixture("payment-required.json");
    Map<String, Object> payload = fixture("payment-payload.json");
    Map<String, Object> settled = fixture("settlement-success.json");
    String requestedId = client.emit(X402Mapper.mapPaymentRequired(required, X402Mapper.ADAPTER_CLIENT, null, null));
    client.emit(X402Mapper.mapPaymentPayload(payload, X402Mapper.ADAPTER_CLIENT, null, requestedId, null));
    client.emit(X402Mapper.mapSettlementResponse(settled, X402Mapper.SettlementContext.from(payload), X402Mapper.ADAPTER_CLIENT, null));
    List<Map<String, Object>> events = events();
    Map<String, Object> requested = events.get(0);
    Map<String, Object> authorized = events.get(1);
    Map<String, Object> settlement = events.get(2);

    Map<String, Object> accept = map(payload.get("accepted"));
    Map<String, Object> auth = map(map(payload.get("payload")).get("authorization"));
    assertEquals("x402:eip155:84532:requested:" + requested.get("id"), map(requested.get("payment")).get("payment_id"));
    assertEquals(Map.of("scheme", "exact", "network", "eip155:84532"), map(requested.get("payment")).get("rail_ref"));
    Map<String, Object> payee = Map.of(
        "type", "api_provider",
        "name", "Access to premium market data",
        "resource_url", "https://api.example.com/premium-data",
        "wallet", Map.of("network", "eip155:84532", "address", accept.get("payTo")));
    assertEquals(payee, requested.get("payee"));
    assertEquals(payee, authorized.get("payee"));
    assertEquals(Map.of("network", "eip155:84532", "address", accept.get("asset"), "symbol", "USDC"),
        map(map(requested.get("payment")).get("amount")).get("asset"));
    String want = "x402:eip155:84532:" + auth.get("nonce");
    assertEquals(want, map(authorized.get("payment")).get("payment_id"));
    assertEquals(want, map(settlement.get("payment")).get("payment_id"));
    assertEquals(settled.get("transaction"), map(map(settlement.get("payment")).get("rail_ref")).get("tx_hash"));
    assertEquals(Map.of("previous_event_id", requested.get("id")), authorized.get("related"));
    assertEquals(Map.of("type", "wallet", "wallet", Map.of("network", "eip155:84532", "address", auth.get("from"))),
        authorized.get("instrument"));
  }

  @Test
  void failedSettlementHasNoTxHashAndABoundedReason() {
    Map<String, Object> failed = fixture("settlement-failure.json");
    failed.put("errorReason", "x".repeat(500));
    Map<String, Object> out = X402Mapper.mapSettlementResponse(failed,
        X402Mapper.SettlementContext.from(fixture("payment-payload.json")), X402Mapper.ADAPTER_CLIENT, null);
    Map<String, Object> payment = map(out.get("payment"));
    assertEquals("payment.failed", out.get("type"));
    assertNull(map(payment.get("rail_ref")).get("tx_hash"));
    assertEquals(128, ((String) payment.get("failure_reason")).length());
  }

  @Test
  void payeeWithoutAResourceOrPayTo() {
    Map<String, Object> bare = Map.of("accepts", List.of(Map.of("scheme", "exact", "network", "eip155:8453", "amount", "1", "asset", "0xabc")));
    Map<String, Object> out = X402Mapper.mapPaymentRequired(bare, X402Mapper.ADAPTER_CLIENT, null, null);
    assertEquals(Map.of("type", "wallet", "name", "x402 payee"), out.get("payee"));
  }

  @Test
  void contextDetailsReachTheEvent() {
    AgentContext ctx = AgentContext.of(Agent.of("agt_1").name("Research agent").framework("langgraph"),
        Owner.of("own_1", "person").country("US"), null);
    Map<String, Object> mapped = X402Mapper.mapPaymentPayload(fixture("payment-payload.json"), X402Mapper.ADAPTER_CLIENT, null, null, ctx);
    assertFalse(mapped.containsKey("agent"));
    client.emit(mapped, ctx);
    Map<String, Object> event = events().get(0);
    assertEquals("langgraph", map(event.get("agent")).get("framework"));
    assertEquals("US", map(event.get("owner")).get("country"));
  }

  @Test
  void payerWalletIsTheAgentWithoutContext() {
    Map<String, Object> payload = fixture("payment-payload.json");
    Map<String, Object> mapped = X402Mapper.mapPaymentPayload(payload, X402Mapper.ADAPTER_CLIENT, null, null, null);
    assertEquals(map(map(payload.get("payload")).get("authorization")).get("from"), map(mapped.get("agent")).get("id"));
  }

  @Test
  void neverGuessesAMissingNetworkOrAsset() {
    for (String missing : List.of("network", "asset")) {
      Map<String, Object> required = fixture("payment-required.json");
      map(list(required.get("accepts")).get(0)).remove(missing);
      IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
          () -> X402Mapper.mapPaymentRequired(required, X402Mapper.ADAPTER_CLIENT, null, null));
      assertTrue(e.getMessage().contains("no " + missing), e.getMessage());
    }
  }

  @Test
  void everyChallengeGetsItsOwnId() {
    Map<String, Object> required = fixture("payment-required.json");
    assertNotEquals(map(X402Mapper.mapPaymentRequired(required, X402Mapper.ADAPTER_CLIENT, null, null).get("payment")).get("payment_id"),
        map(X402Mapper.mapPaymentRequired(required, X402Mapper.ADAPTER_CLIENT, null, null).get("payment")).get("payment_id"));
  }

  @Test
  void decodesStandardAndUrlSafeBase64() {
    String enc = X402Headers.encode(Map.of("a", "??>>"));
    for (String h : List.of(enc, enc.replace('+', '-').replace('/', '_').replaceAll("=+$", ""))) {
      assertEquals("??>>", X402Headers.decode(h).get("a"), h);
    }
    assertThrows(IllegalArgumentException.class, () -> X402Headers.decode("not base64!"));
  }

  /** A fake x402 resource: 402 with a challenge, then 200 with a settlement once paid. */
  static HttpServer resource() throws IOException {
    String required = X402Headers.encode(fixture("payment-required.json"));
    String settled = X402Headers.encode(fixture("settlement-success.json"));
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      exchange.getRequestBody().readAllBytes();
      if (exchange.getRequestHeaders().getFirst(X402Headers.PAYMENT_SIGNATURE) == null) {
        exchange.getResponseHeaders().add(X402Headers.PAYMENT_REQUIRED, required);
        exchange.sendResponseHeaders(402, -1);
      } else {
        exchange.getResponseHeaders().add(X402Headers.PAYMENT_RESPONSE, settled);
        byte[] body = "{\"ok\":true}".getBytes();
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
      exchange.close();
    });
    server.start();
    return server;
  }

  @Test
  void httpClientRecordsAPaidRequest() throws Exception {
    HttpServer server = resource();
    try {
      URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/premium-data");
      X402HttpClient http = X402HttpClient.wrap(HttpClient.newHttpClient(), client);
      assertEquals(402, http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
      HttpRequest paid = HttpRequest.newBuilder(uri).header(X402Headers.PAYMENT_SIGNATURE, X402Headers.encode(fixture("payment-payload.json")))
          .PUT(HttpRequest.BodyPublishers.noBody()).build();
      assertEquals(200, http.sendAsync(paid, HttpResponse.BodyHandlers.ofString()).get().statusCode());
    } finally {
      server.stop(0);
    }
    List<Map<String, Object>> events = events();
    assertEquals(List.of("payment.requested", "payment.authorized", "payment.settled"),
        events.stream().map(e -> e.get("type")).toList());
    assertEquals(events.get(0).get("id"), map(events.get(1).get("related")).get("previous_event_id"));
    assertEquals(map(events.get(1).get("payment")).get("payment_id"), map(events.get(2).get("payment")).get("payment_id"));
  }

  @Test
  void observerNeverBreaksTheRequest() {
    X402Observer observer = X402Observer.forClient(client);
    Map<String, Object> payload = observer.onRequest("https://x/a", name -> "also not base64", null);
    observer.onResponse("https://x/a", 402, name -> name.equals(X402Headers.PAYMENT_REQUIRED) ? "%%%" : null, payload, null);
    observer.onRequest("https://x/a", name -> {
      throw new IllegalStateException("header lookup failed");
    }, null);
    client.close();
    assertEquals(2, errors.size());
    assertEquals(HalvorError.INVALID_EVENT, errors.get(0).code());
  }

  @Test
  void serverObserverUsesTheSellersIdentity() {
    X402Observer observer = X402Observer.forServer(client, Payee.of("api_provider").name("Market data API"));
    String required = X402Headers.encode(fixture("payment-required.json"));
    String settled = X402Headers.encode(fixture("settlement-success.json"));
    String signature = X402Headers.encode(fixture("payment-payload.json"));
    observer.onResponse("/premium-data", 402, name -> name.equals(X402Headers.PAYMENT_REQUIRED) ? required : null, null, null);
    Map<String, Object> payload = observer.onRequest("/premium-data", name -> name.equals(X402Headers.PAYMENT_SIGNATURE) ? signature : null, null);
    observer.onResponse("/premium-data", 200, name -> name.equals(X402Headers.PAYMENT_RESPONSE) ? settled : null, payload, null);
    List<Map<String, Object>> events = events();
    assertEquals(3, events.size());
    for (Map<String, Object> e : events) {
      assertEquals(X402Mapper.ADAPTER_SERVER, map(e.get("source")).get("adapter"));
      assertEquals("Market data API", map(e.get("payee")).get("name"));
    }
    assertNull(events.get(1).get("related"));
  }
}
