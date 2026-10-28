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
  `id.timestamp.body`, with a `whsec_` base64 key. `hmacSignature(...)` building another of the same
  family is left for when a third sender needs one; the format is internal until then.
- **Order:** the strict body is read as bytes (core already decodes from a `ByteArray`), the timestamp checked
  against the API's clock within `tolerance`, each presented signature compared with each secret's using
  `MessageDigest.isEqual`, and only then is the body decoded. A failure never reaches the handler.
- **Rotation:** any secret matching any signature passes, so old and new overlap; Stripe sends one `v1` per secret.
- **A declared failure:** 400 in the refusal envelope, one `error` for all causes (missing header, stale, mismatch),
  the cause in the log and not the body. Declared by `signedBy`, so the document lists it.
- **The document:** the scheme's headers as required header parameters, described ("HMAC-SHA256 over ..."), on the
  endpoint or the webhook. OpenAPI has no security scheme for an HMAC, so none is invented.
- **MCP:** a `signedBy` endpoint is refused as a tool; a model cannot sign.
- **Testing:** `pelican-test` signs a request as the sender would, `request(stripeEvent, event).signedWith(stripe,
  secret, at = clock.instant())`, so a test sends a valid, stale or altered event without computing an HMAC by hand.

## Why this shape

The check is declared on the endpoint, like `idempotencyKey()`, so the document, the generated sender and the route
read one value; a filter could not run before decoding and is invisible to all three. Option A puts the check in
core around the body decode, once for every backend. Option B adds a raw-bytes view to `Params` and leaves the check
to a `before` filter, which works but keeps the body decoded before the check and is written by every service.
Recommended: A.

## Stack

- [x] **`spec-0097-schemes`** ([#418](https://github.com/matthewjones372/pelican/pull/418)): `HmacScheme`, Stripe
      and Standard Webhooks parsing, signing and verifying, as values. Done when: Stripe's and the Standard Webhooks
      spec's published test vectors verify, and a stale or altered one does not, with a fixed clock.
- [x] **`spec-0097-verify`** ([#419](https://github.com/matthewjones372/pelican/pull/419)): `signedBy` on an endpoint,
      checked in core before decode on every backend's read, the declared 400, `RefusalReason.SIGNATURE`. Done when:
      in process, a forged, stale or unsigned request is a 400, `onRefusal` hears it, and the handler did not run.
- [x] **`spec-0097-parity`** ([#420](https://github.com/matthewjones372/pelican/pull/420), split from verify):
      `signedWith` in `pelican-test`, and a signed endpoint in the backends example. Done when: every backend answers
      a signed, forged and stale delivery alike.
- [x] **`spec-0097-sign`** ([#421](https://github.com/matthewjones372/pelican/pull/421)): `signedBy` on a
      `webhook(...)`, the generated sender's `signer` parameter, the document's headers. Done when: a generated
      sender's request verifies against the receiving endpoint, in process.
- [x] **`spec-0097-docs`**: reference, cookbook (Stripe end to end), changelog, and MCP's refusal of a signed
      endpoint.

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
5. **Does `onRefusal` hear a bad signature?** Specs 0093 to 0096 report every refusal Pelican answers. Recommended: yes,
   as a new `RefusalReason.SIGNATURE` (`signature`), with the cause (missing, stale, mismatch) in the log only.

## Decisions

Matt took each recommendation on 2026-10-28.

1. **A bad signature is a 400**, in the refusal envelope; no 401, since there is no challenge to name.
2. **The tolerance is 5 minutes by default**, and a timestamp that far in the future is refused the same way.
3. **The handler gets the decoded body**; a service that wants the raw bytes declares `rawBody()` without `signedBy`.
4. **`pelican-import` reads the headers as plain headers**, not back into a scheme.
5. **`onRefusal` hears a bad signature** as `RefusalReason.SIGNATURE`, with the cause in the log only.
