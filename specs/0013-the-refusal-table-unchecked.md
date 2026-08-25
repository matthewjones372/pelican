# 0013 — The refusal table, unchecked

## Problem

`ServerErrors.described` is the table deciding what one throwable becomes on
the wire, and its KDoc says why it is in core: "the failures this library
describes, and what each one is told to a caller… decided in core so the three
backends cannot drift." Six entries — `ApiException`, `DecodeFailure`,
`BodyDecodeFailure`, `NotAcceptable`, `PayloadTooLarge`, and null for everything
else.

Three of the six have never been asked of all three backends.

- **`BodyDecodeFailure` → 400.** Nothing anywhere sends malformed JSON to a
  `jsonBody` endpoint and checks the status across backends. The nearest cases
  are a *form* field that will not decode and a body that is not multipart at
  all, both in `CookiesFormsAndUploadsTest`. Each interpreter wraps its own
  codec's throwable, so this is exactly the path most likely to differ.
- **The missing-required-input 400s.** Each interpreter raises
  `ApiException(400, "Missing required query parameter '…'")` and its header and
  cookie equivalents from its own decode loop — three copies of one message.
  `AllBackendsTest` covers a path parameter that *does not decode*; nothing
  covers an input that is simply absent.
- **`NotAcceptable` → 406.** `ContentNegotiationTest` covers it per backend,
  written out three times rather than parameterised, so nothing compares the
  bodies and a change to one is not a change to the others.

Every one of these is a status a caller sees on a bad day, and all three are
served by code written three times.

## Not doing

- No change to the table, the statuses or the messages. This adds the tests
  that say the three backends agree about what is already there.
- Not `PayloadTooLarge`, which spec 0005 covers, or `ApiException`, which the
  filter and multipart suites already exercise on all three.
- No new descriptions. Everything needed is already declared in
  `example/backends/Greetings.kt` — `note`, `traceId`, `locale`, `shout`.
- Not the 500 path. `renderError`'s reference and hook are core's and tested
  there; what a backend does with an unexpected throwable is spec 0007.

## Shape

Four parameterised cases in the suite that already runs three ways:

```kotlin
@ParameterizedTest @MethodSource("backends")
fun `a body that is not JSON at all is a 400, not a 500`(name: String, client: ApiClient) {
    client.transport.send(client.request(echo, In2(null, Note("x"))).withBody("{ nope"))
        .shouldHaveStatus(400)
        .body shouldContain "Malformed request body"
}

@ParameterizedTest @MethodSource("backends")
fun `a required query parameter left off is a 400 naming it`(…)   // and header, and cookie
```

and `ContentNegotiationTest`'s three hand-written copies collapsed into one
parameterised case, keeping whatever is genuinely backend-shaped.

## Why this shape

The table exists so three interpreters cannot answer one condition three ways.
It is checked by reading. The suites that would catch a drift are already built
and already parameterised, so the cost here is test bodies rather than
machinery — which is the argument for doing it now rather than after the first
report of a 500 where a 400 was documented.

The alternative is asserting these in each backend's own module, beside the
interpreter that raises them. Cheaper to write, and it is three assertions that
can drift the same way the code did. Not recommended.

## Stack

- [x] **`spec-0013-refusal-parity`** — malformed JSON, and a required query parameter, header and cookie left off; `ContentNegotiationTest` parameterised.
      Done when: each condition is one case answering one status and one body shape on all three, and deleting a clause from `described` turns three invocations red rather than one. Landed in [#39](https://github.com/matthewjones372/pelican/pull/39).

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Should the body shape be asserted, or only the status? Recommend the shape —
   `ApiError` is rendered by hand precisely so a failing codec cannot stop the
   error being reported, and nothing checks that the three render it alike.
2. `DecodeFailure`'s message quotes the raw value. Worth asserting it is not
   quoted back for a header that might carry a credential? Recommend raising it
   as a finding rather than a test: it is a question about the message, not
   about parity.
3. Does collapsing `ContentNegotiationTest` lose the per-backend unregistered-status
   assertion it also makes? Recommend keeping that half per backend and moving
   only the 406.
