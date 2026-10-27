# 0097 — A webhook that proves who sent it

## Problem

A service receiving Stripe's events has to check `Stripe-Signature` over the exact bytes Stripe sent, before anything
decodes them, and refuse a stale timestamp so a captured request cannot be replayed. Pelican decodes a JSON body
before the handler runs, so the handler never sees those bytes; the service declares `rawBody()` and decodes by hand,
and the document then shows an opaque body. Doorlist plans `WebhookSignatures` for Stripe, SES and SMS callbacks and
for signing its own outbound events in phase 3 (dependencies.md item 7). Pelican describes outbound `webhook(...)`
and its generated sender, but signs nothing, and a receiver is told only that an `X-Signature` header exists.

## Not doing

- **SNS.** Its signatures are RSA over a canonical string, with the certificate fetched from a URL in the message.
  That fetch is an outbound call needing its own host rules (Doorlist's `SafeFetch`, item 16), so it is a later spec.
- **Deduplication.** A signed event can still arrive twice; the service keys on its id, or a later spec maps
  `webhook-id` onto 0092's store.
- **Secret storage and rotation schedules.** The service supplies the current secrets; Pelican tries each.
- **A streamed body.** `signedBy` on a `rawBody()`, multipart or NDJSON input is refused when the endpoint is built.

## Shape

```kotlin
val stripe = stripeSignature(tolerance = 5.minutes) { listOf(current, previous) }   // secrets, read per request
val hooks = standardWebhooks(tolerance = 5.minutes) { secrets() }                   // "whsec_..." keys

val stripeEvent = endpoint {
    post("hooks" / "stripe")
    signedBy(stripe)                          // checked over the bytes received, then the body decodes as usual
    body(jsonBody<StripeEvent>())
    empty(200)
}

val orderPlaced = webhook("orderPlaced") { signedBy(hooks); body(orderPlacedEvent); empty(204) }
client.orderPlaced(url, order, signer = hooks.signer(secret))   // the generated sender adds the three headers
```

- **Two schemes, both HMAC-SHA256 in core** (`javax.crypto`, no dependency). Stripe: `t=<unix>,v1=<hex>[,v1=...]`
  over `t.body`. Standard Webhooks: `webhook-id`, `webhook-timestamp`, `webhook-signature: v1,<base64> ...` over
  `id.timestamp.body`, with a `whsec_` base64 key. `hmacSignature(...)` builds another of the same family.
- **Order:** the strict body is read as bytes (core already decodes from a `ByteArray`), the timestamp checked
  against the API's clock within `tolerance`, each presented signature compared with each secret's using
  `MessageDigest.isEqual`, and only then is the body decoded. A failure never reaches the handler.
- **Rotation:** any secret matching any signature passes, so old and new overlap; Stripe sends one `v1` per secret.
- **A declared failure:** 400 in the refusal envelope, one `error` for all causes (missing header, stale, mismatch),
  the cause in the log and not the body. Declared by `signedBy`, so the document lists it.
- **The document:** the scheme's headers as required header parameters, described ("HMAC-SHA256 over ..."), on the
  endpoint or the webhook. OpenAPI has no security scheme for an HMAC, so none is invented.
- **MCP:** a `signedBy` endpoint is refused as a tool; a model cannot sign.

## Why this shape

The check is declared on the endpoint, like `idempotencyKey()`, so the document, the generated sender and the route
read one value; a filter could not run before decoding and is invisible to all three. Option A puts the check in
core around the body decode, once for every backend. Option B adds a raw-bytes view to `Params` and leaves the check
to a `before` filter, which works but keeps the body decoded before the check and is written by every service.
Recommended: A.

## Stack

- [ ] **`spec-0097-schemes`**: `HmacScheme`, Stripe and Standard Webhooks parsing, signing and verifying, as values.
      Done when: Stripe's and the Standard Webhooks spec's published test vectors verify, and a stale or altered one
      does not, with a fixed clock.
- [ ] **`spec-0097-verify`**: `signedBy` on an endpoint, checked in core before decode, the declared 400, refusals.
      Done when: on every backend a forged, stale or unsigned request is a 400 and the handler did not run.
- [ ] **`spec-0097-sign`**: `signedBy` on a `webhook(...)`, the generated sender's `signer` parameter, the document's
      headers. Done when: a generated sender's request verifies against the receiving endpoint, in process.
- [ ] **`spec-0097-docs`**: reference, cookbook (Stripe end to end), changelog.

## Acceptance

```bash
./gradlew build   # parity suite, goldens, checkOrdersClient, ImportedOrdersTest
```

## Open questions

1. **400 or 401 for a bad signature?** A 401 must carry `WWW-Authenticate`, and there is no challenge to name.
   Recommended: 400, as Stripe and the Standard Webhooks spec both say a receiver answers.
2. **Default tolerance?** Recommended: 5 minutes, both providers' default, and a timestamp too far in the future is
   refused the same way.
3. **Should the handler see the raw bytes as well?** Stripe's SDK wants them to build its own event. Recommended: no;
   the handler gets the decoded body, and a service that prefers the SDK declares `rawBody()` without `signedBy`.
4. **Does `pelican-import` read the scheme back?** Recommended: not in this spec; it reads the headers as plain headers.
