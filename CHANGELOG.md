# Changelog

## 0.1.0 (developer preview)

- `HalvorClient`: validates, redacts, signs (EdDSA, detached JWS over RFC 8785) and delivers Agent Payment Events in batches from a daemon thread. `emit` never throws or waits for I/O. Configured with a builder, or from `HALVOR_API_KEY`, `HALVOR_SOURCE_KEY` and `HALVOR_ENDPOINT`.
- Delivery rules shared with the Node.js, Python and Go SDKs: retries with backoff on network errors, 408, 425, 429 and 5xx; permanent errors dead-lettered and reported once with the API's code and request id; batches that are too large split; per-event results counted.
- Disk spool (`spoolDir`, 50 MB and 7-day limits) in the same format as the other SDKs, replayed by the next `flush` or process. Queued events are flushed at JVM shutdown.
- `AgentContext`: attributes payments to an agent, owner and instruction per thread, with `wrap` for thread pools; instruction text is hashed locally.
- Typed event builders (`Event`, `Payment`, `Amount`, `Payee`, …) or plain maps.
- Card numbers, CVCs, API keys and private keys are removed from free-text fields and JSON evidence before signing.
- `Ape.validateEvent`, `Ape.signEvent`, `Ape.verifyEvent`, `Jwk.generate` and the `jcs` package, byte-identical to the other SDKs.
- `stripe`: maps PaymentIntent, refund, dispute and Stripe Issuing webhooks; `tagMetadata` attributes them to an agent. No dependency on stripe-java.
- `x402`: a `java.net.http.HttpClient` wrapper and an `X402Observer` for any client or server stack.
- Passes the ape-schema conformance cases (40/40) and the shared signing vector.
- No runtime dependencies. Java 17 or later.
