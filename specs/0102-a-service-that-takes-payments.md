# 0102 — A service that takes payments

## Problem

Specs 0092 to 0097 each have an example of their own: a tip with an idempotency key, `whoAmI` with a caller, a
webhook on every backend. No single example shows them together, though a service that needs one usually needs all
three. A service taking payments needs to know which merchant is calling. A charge must be safe to retry. The
processor's word that a charge settled must be checked before it is believed.

A reader building that today has to put three examples together, and has to work out for themselves how the
features interact. For example, an idempotency key belongs to one merchant, a refused caller spends no key, and the
webhook has no caller at all. The brief asks for one example combining idempotency, auth and webhook signatures.

## Not doing

- **Tenants and rate limits.** Each already has its own example and golden document (0095, 0096). Adding them here
  doubles the setup without showing anything new about the three this example is for.
- **Extending `example.shop`.** The shop is the document-quality example, and its golden document and its clients
  are pinned. Three features there would change every pinned file at once. A small service of its own keeps each
  feature visible.
- **Persistence.** The ledger, the idempotency store and the secrets are in memory, as in every other example.
  Persistent stores live in Surefold.
- **A real processor.** The processor is played by a test, signing with the generated client's `signer`.
- **Deduplicating webhook deliveries.** This is out of scope, as in 0097. Settling a charge twice leaves it settled,
  so the handler is already safe to run twice.

## Shape

`example/src/main/kotlin/example/payments/Payments.kt` holds the whole service:

```kotlin
val merchantKey = bearerAuth(name = "merchantKey")
val merchant = authenticated(merchantKey, Identity::subject)

val processor = standardWebhooks { listOf(PROCESSOR_SECRET) }

val createCharge = endpoint(jsonBody<NewCharge>()) {
    post("charges")
    operationId = "createCharge"
    authenticatedBy(merchant)
    requires(permission("charges:write"))
    idempotencyKey()                         // a retried POST is answered with the first charge
    json<Charge>(201)
}

val noSuchCharge = errorJson<ChargeNotFound>(404, "No charge with that id")

val getCharge = endpoint(chargeId) {
    get("charges" / chargeId)
    operationId = "getCharge"
    authenticatedBy(merchant)
    requires(permission("charges:read"))
    json<Charge>() orFail noSuchCharge       // another merchant's charge is not found, never forbidden
}

val chargeSettled = endpoint(jsonBody<ProcessorEvent>()) {
    post("hooks" / "processor")
    operationId = "processorEvent"
    signedBy(processor)                      // no caller: the signature is who sent it
    empty(204)
}

fun paymentsApi(ledger: Ledger = Ledger()): Api = api(routes(ledger), codecs = JacksonCodecs) {
    authenticate(merchantKey, merchantKeys)  // sk_test_ada: read and write; sk_test_viewer: read
    idempotency(InMemoryIdempotencyStore(), ttl = 24.hours)
}
```

- **The ledger** is a small in-memory map of charges, each `pending`, then `settled` or `declined`. It also counts
  how many charges were created, so a test can show a retried POST created one.
- **Merchant keys** are two fixed bearer tokens. They map to an `Identity` whose `scope` claim names the
  permissions. An unknown key is a 401 that does not repeat the key back.
- **What the tests show**, on Jetty and through the generated client:
  - **Idempotency:** a POST retried with the same key returns the first charge, and the ledger counts one. The same
    key with another amount is a 422. Two merchants using the same key get two charges, because a key is scoped to
    its caller.
  - **Auth:** no key is a 401. The viewer key is a 403 on `createCharge` and spends no idempotency key. Another
    merchant's charge is a 404.
  - **Signatures:** a delivery signed with the processor secret settles the charge. A forged or stale one is a 400
    `signature_invalid`, and the charge stays `pending`.
- **The generated client** is `PaymentsClient`, generated into build/ like the whoAmI and tipHooks clients:
  - `createCharge` sends an `Idempotency-Key` of its own, so wrapping its transport in `retrying()` is safe.
  - `processorEvent` takes a `signer`, so the test plays the processor with `processor.signer(PROCESSOR_SECRET)`.
- **The document** is pinned by `golden/payments/openapi.json`. It shows:
  - the bearer scheme and the 401 and 403;
  - the `Idempotency-Key` header with its 409 and 422;
  - the signature headers and their tagged 400.

## Why this shape

One endpoint per feature, all in one small service, keeps each feature readable on its own while the tests show the
interactions. A refused caller spends no key, a key is scoped to its caller, and a webhook has a signature in place
of a caller. Those interactions are what the separate examples cannot show. The alternative is to grow
`example.shop`. That would make the example look more like a real service, but it would bury three short
declarations in the largest file in the examples and churn its pinned clients. The recommendation is a service of
its own.

## Stack

- [x] **`spec-0102-service`** ([#424](https://github.com/matthewjones372/pelican/pull/424)): `example.payments`
      with its three endpoints, the ledger, merchant keys and `PaymentsTest` on Jetty. Done when: the idempotency,
      auth and signature claims above each pass as a test.
- [x] **`spec-0102-client`** ([#425](https://github.com/matthewjones372/pelican/pull/425)): `generatePaymentsClient`,
      `golden/payments/openapi.json`, and `PaymentsClientTest`, in which a merchant retries through `retrying()` and
      the processor signs with `signer`. Done when: the golden document is accepted and both sides go through the
      generated client.
- [x] **`spec-0102-docs`**: a "Payments: the three together" section in `docs/a-whole-service.md`, a pointer from
      the three cookbook entries, and the changelog. Done when: each link resolves to the example's code.

## Acceptance

```bash
./gradlew build   # PaymentsTest, PaymentsClientTest, PaymentsGoldenTest
```

## Decisions

Matt took each recommendation on 2026-10-28.

1. **Jetty only.** Every backend's handling of each feature is already covered by the backends suite.
2. **No deduplication of the webhook's event id.** Settling twice leaves a charge settled, and the example says so
   in a comment.
3. **The viewer key's 403 is shown through the generated client** as an `ApiCallFailed` with status 403, as well as
   on the wire.
