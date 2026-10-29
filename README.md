# HalvorAI Java SDK

Signed, attributed payment events for AI agents. HalvorAI records each payment an agent makes, with the agent, the owner it acted for, the instruction it followed and the network proof that came with it. It is never in the payment path: it cannot slow or block a payment.

> **Developer preview.** The SDK works offline today: build, sign and inspect events with your own sink. Sandbox API keys go to early-access teams. [Request access](https://halvorai.com/?utm_source=github&utm_campaign=ingest_java#early-access).

- Docs: [docs.halvorai.com](https://docs.halvorai.com)
- Event format: [Agent Payment Event (APE) v0.1](https://schema.halvorai.com), open and Apache-2.0
- Java 17 or later; works from Kotlin and other JVM languages. **No runtime dependencies**: Ed25519, HTTP and JSON come from the JDK.
- Events are byte-identical to the [Node.js](https://github.com/halvorAI/ingest-js), [Python](https://github.com/halvorAI/ingest-python) and [Go](https://github.com/halvorAI/ingest-go) SDKs.

## Install

Maven:

```xml
<dependency>
  <groupId>com.halvorai</groupId>
  <artifactId>halvorai-ingest</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("com.halvorai:halvorai-ingest:0.1.0")
```

## Try it offline

No account needed. Events are signed with your own Ed25519 key and handed to your `sink` instead of the API.

```java
import com.halvorai.ingest.*;
import com.halvorai.ingest.jcs.Jcs;
import java.time.Duration;

Jwk key = Jwk.generate("src_local"); // private key; keep it secret
HalvorClient halvor = HalvorClient.builder()
    .sourceKey(key)
    .sink(envelopes -> envelopes.forEach(e -> System.out.println(Jcs.canonicalize(e.event()))))
    .build();

AgentContext context = AgentContext.of(
    Agent.of("agt_travel_12").name("Travel booking agent"),
    Owner.of("own_u_4410", "person").country("US"),
    Instruction.of("task_8890", "Book a return flight to Austin under $500"));

try (var scope = context.open()) {
  halvor.emit(Event.of(Event.PAYMENT_SETTLED,
      Payment.of("pi_3QxAbCdEfGhIjKlM", "stripe", Amount.minor("41200", "USD"), "settled")
          .railRef("payment_intent", "pi_3QxAbCdEfGhIjKlM"),
      Payee.of("merchant").name("Example Airways")));
}

halvor.close(Duration.ofSeconds(5));
```

`emit` returns the event id at once and never throws. Instruction text is hashed on your machine and never sent. Anyone with the public half of the key (`key.toPublic()`) can check an envelope with `envelope.verify(publicKey)`.

You can also pass a `Map<String, Object>` to `emit`, in the shape of the [field reference](https://schema.halvorai.com).

### Agent context and threads

`AgentContext` is held per thread. A thread pool does not inherit it, so capture it when you hand work over:

```java
executor.submit(AgentContext.wrap(() -> halvor.emit(event)));
```

Or pass it explicitly: `halvor.emit(event, context)`.

## Send to HalvorAI (early access)

With a sandbox key (`hk_test_…`) and a source key registered in the dashboard, set `HALVOR_API_KEY` and `HALVOR_SOURCE_KEY` (the private JWK as JSON) and build the client with no options:

```java
HalvorClient halvor = HalvorClient.builder()
    .onError(error -> log.warn(error.toString()))
    .build();
```

A missing or malformed key never throws: the client reports one `config` error and drops events until it is fixed.

## Delivery

- Events are sent every `flushInterval` (default 1 s) from a daemon thread, or sooner when a batch of `batchSize` (100) is full.
- Network errors, 408, 425, 429 and 5xx are retried with backoff (`retryDelays`, default 1, 2, 4, 8, 16, 32 s). Permanent errors (400, 401, 403, 404, 405, 409, 410, 415, 422) are not retried; the batch is reported through `onError` with the API's `serverCode` and `requestId`.
- Set `spoolDir` to keep batches that could not be delivered on disk. The next `flush` or process replays them. Rejected batches go to `<spoolDir>/dead/`. Limits: `spoolMaxBytes` (50 MB) and `spoolMaxAge` (7 days), oldest first. The spool format is shared with the other SDKs.
- `stats()` returns counts: emitted, accepted, duplicates, rejected, dropped, retries, spooled, replayed, and the dropped reasons.

Queued events are flushed for up to 2 seconds when the JVM shuts down normally (`exitHook(false)` turns this off). `HalvorClient` is `AutoCloseable`; in Spring, register it as a bean and it is closed with the context. In a serverless handler or a short job, flush before you return:

```java
boolean drained = halvor.flush(Duration.ofSeconds(3));
```

## Stripe

Map Stripe webhooks after you check their signature. No dependency on stripe-java:

```java
import com.halvorai.ingest.stripe.StripeEvents;

Event event = Webhook.constructEvent(payload, sigHeader, endpointSecret); // stripe-java
StripeEvents.emit(halvor, payload); // the raw body you just verified
```

PaymentIntents, refunds, disputes and Stripe Issuing authorizations and transactions are recorded. To attribute them to an agent, tag the PaymentIntent or Issuing card when you create it:

```java
params.putAllMetadata(StripeEvents.tagMetadata(Map.of()));
```

## x402

Wrap the `java.net.http.HttpClient` your agent pays with. Payment requirements, signed payment payloads and settlement responses become events. Headers are only read; nothing in the payment path changes.

```java
import com.halvorai.ingest.x402.X402HttpClient;

X402HttpClient http = X402HttpClient.wrap(HttpClient.newHttpClient(), halvor);
HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
```

Each 402 challenge becomes a `payment.requested` event. The payment that answers it becomes `payment.authorized`, linked to the challenge with `related.previous_event_id`, and the settlement becomes `payment.settled` or `payment.failed` with the same `payment_id`. A challenge without a network or token address is reported as `invalid_event` and not recorded.

Another HTTP client, or selling over x402? `X402Observer` works with any stack: call `onRequest` with the request headers and `onResponse` with the response.

```java
X402Observer observer = X402Observer.forServer(halvor, Payee.of("api_provider").name("Market data API"));
// in a filter or interceptor, inside your x402 payment middleware:
Map<String, Object> payload = observer.onRequest(url, request::getHeader, null);
chain.doFilter(request, response);
observer.onResponse(url, response.getStatus(), response::getHeader, payload, null);
```

## Develop

```bash
mvn verify
```

The tests run the [ape-schema conformance cases](https://github.com/halvorAI/ape-schema/tree/main/conformance) (40 events and the JCS vectors), the signing vector shared with the other SDKs, and 1,500 random values canonicalized by the TypeScript SDK.

## Security

Report vulnerabilities privately to hello@halvorai.com.

## License

Apache-2.0
