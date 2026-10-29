package com.halvorai.ingest.stripe;

import static com.halvorai.ingest.TestSupport.map;
import static com.halvorai.ingest.TestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.halvorai.ingest.Agent;
import com.halvorai.ingest.AgentContext;
import com.halvorai.ingest.Ape;
import com.halvorai.ingest.Envelope;
import com.halvorai.ingest.HalvorClient;
import com.halvorai.ingest.HalvorError;
import com.halvorai.ingest.Instruction;
import com.halvorai.ingest.Jwk;
import com.halvorai.ingest.Owner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The fixtures are the Node.js SDK's (src/stripe/fixtures), Stripe test-mode shapes. */
class StripeEventsTest {
  static final List<String> FILES = List.of(
      "charge.dispute.created.json", "charge.refunded.json", "issuing_authorization.created.json",
      "issuing_authorization.updated.json", "issuing_transaction.created.json", "issuing_transaction.refund.json",
      "payment_intent.amount_capturable_updated.json", "payment_intent.payment_failed.json",
      "payment_intent.succeeded.json", "unattributed.json");

  static Map<String, Object> mapped(String file) {
    return StripeEvents.map(resource("stripe/" + file));
  }

  @Test
  void mapsEveryFixture() {
    String[][] cases = {
      {"payment_intent.amount_capturable_updated.json", "payment.authorized", "authorized", "stripe"},
      {"payment_intent.succeeded.json", "payment.settled", "settled", "stripe"},
      {"payment_intent.payment_failed.json", "payment.failed", "failed", "stripe"},
      {"charge.refunded.json", "payment.refunded", "refunded", "stripe"},
      {"charge.dispute.created.json", "payment.disputed", "disputed", "stripe"},
      {"issuing_authorization.created.json", "payment.authorized", "authorized", "stripe-issuing"},
      {"issuing_authorization.updated.json", "payment.failed", "failed", "stripe-issuing"},
      {"issuing_transaction.created.json", "payment.settled", "settled", "stripe-issuing"},
      {"issuing_transaction.refund.json", "payment.refunded", "refunded", "stripe-issuing"},
    };
    for (String[] c : cases) {
      Map<String, Object> m = mapped(c[0]);
      assertEquals(c[1], m.get("type"), c[0]);
      assertEquals(c[2], map(m.get("payment")).get("status"), c[0]);
      assertEquals(c[3], map(m.get("source")).get("adapter"), c[0]);
    }
  }

  @Test
  void ignoresUnrelatedTypes() {
    assertNull(StripeEvents.map("{\"type\":\"customer.created\",\"data\":{\"object\":{\"id\":\"cus_x\"}}}"));
  }

  @Test
  void unattributedWhenMetadataIsMissing() {
    Map<String, Object> m = mapped("unattributed.json");
    assertEquals("unattributed", map(m.get("agent")).get("id"));
    assertEquals("unattributed", map(m.get("owner")).get("id"));
  }

  @Test
  void failureReasonFromLastPaymentError() {
    assertEquals("card_declined", map(mapped("payment_intent.payment_failed.json").get("payment")).get("failure_reason"));
  }

  @Test
  void issuingMerchantData() {
    Map<String, Object> m = mapped("issuing_authorization.created.json");
    Map<String, Object> payment = map(m.get("payment"));
    Map<String, Object> ref = map(payment.get("rail_ref"));
    Map<String, Object> payee = map(m.get("payee"));
    assertEquals("card", payment.get("rail"));
    assertEquals("iauth_1QxYz2AbCdEfGh", ref.get("authorization_id"));
    assertEquals("stripe-issuing", ref.get("processor"));
    assertEquals("Example Airways", payee.get("name"));
    assertEquals("4511", payee.get("mcc"));
    assertEquals("US", payee.get("country"));
    assertEquals("acq_000123456789", payee.get("id"));
    assertEquals("agt_travel_12", map(m.get("agent")).get("id"));
  }

  @Test
  void everyFixtureProducesAValidSignedEvent() {
    for (String file : FILES) {
      Jwk key = Jwk.generate("src_01STRIPEADAPTERTEST000000001");
      List<Envelope> got = Collections.synchronizedList(new ArrayList<>());
      List<HalvorError> errors = Collections.synchronizedList(new ArrayList<>());
      HalvorClient client = HalvorClient.builder().sourceKey(key).exitHook(false).sink(got::addAll).onError(errors::add).build();
      Optional<String> id = StripeEvents.emit(client, resource("stripe/" + file));
      client.close(Duration.ofSeconds(5));
      assertTrue(id.isPresent() && id.get().startsWith("evt_"), file);
      assertEquals(List.of(), errors, file);
      assertEquals(1, got.size(), file);
      assertEquals(List.of(), Ape.validateEvent(got.get(0).event()), file);
      assertTrue(got.get(0).verify(key.toPublic()), file);
    }
  }

  @Test
  void emitObjectWithAStripeJavaStyleEvent() {
    List<Envelope> got = Collections.synchronizedList(new ArrayList<>());
    HalvorClient client = HalvorClient.builder().sourceKey(Jwk.generate("src_local")).exitHook(false).sink(got::addAll).build();
    assertTrue(StripeEvents.emitObject(client, "invoice.paid", 0, "{}").isEmpty());
    String object = "{\"id\":\"pi_3QxAbCdEfGhIjKlM\",\"amount\":41200,\"currency\":\"usd\",\"metadata\":{\"halvor_agent_id\":\"agt_1\"}}";
    assertTrue(StripeEvents.emitObject(client, "payment_intent.succeeded", 1760000000L, object).isPresent());
    client.close(Duration.ofSeconds(5));
    Map<String, Object> event = got.get(0).event();
    Map<String, Object> amount = map(map(event.get("payment")).get("amount"));
    assertEquals("41200", amount.get("value"));
    assertEquals("USD", amount.get("currency"));
    assertEquals("2025-10-09T08:53:20.000Z", event.get("occurred_at"));
    assertEquals("agt_1", map(event.get("agent")).get("id"));
  }

  @Test
  void badPayloadsAreReportedNotThrown() {
    List<HalvorError> errors = new ArrayList<>();
    HalvorClient client = HalvorClient.builder().sourceKey(Jwk.generate("src_local")).exitHook(false)
        .sink(e -> {}).onError(errors::add).build();
    assertTrue(StripeEvents.emit(client, "not json").isEmpty());
    assertTrue(StripeEvents.emit(client, "{\"type\":\"payment_intent.succeeded\",\"data\":{}}").isEmpty());
    assertEquals(2, errors.size());
    client.close();
  }

  @Test
  void tagMetadata() throws Exception {
    AgentContext ctx = AgentContext.of(Agent.of("agt_ctx_1").name("Ctx"), Owner.of("own_ctx_1", "business"),
        Instruction.of("ins_ctx_1", "buy coffee"));
    Map<String, String> tagged = ctx.call(() -> StripeEvents.tagMetadata(Map.of("order", "1")));
    assertEquals(Map.of("order", "1", "halvor_agent_id", "agt_ctx_1", "halvor_owner_id", "own_ctx_1",
        "halvor_owner_type", "business", "halvor_instruction_id", "ins_ctx_1"), tagged);
    Map<String, String> overridden = ctx.call(() -> StripeEvents.tagMetadata(null, "agt_override", null, null, null));
    assertEquals("agt_override", overridden.get("halvor_agent_id"));
    assertEquals("own_ctx_1", overridden.get("halvor_owner_id"));
  }
}
