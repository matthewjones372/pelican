# Approved complete Jetty admission implementation plan

Approved by independent root technical review under delegated programme authority, 30 October 2026, against
spec 0104's complete Jetty entry. SPI PR 438 merged at `d868bf2f2a4af63d398efa0ddb9c39e09d373c91`; required
[main build 41130856242](https://github.com/matthewjones372/pelican/actions/runs/41130856242) completed successfully
for both JDK builds, artifacts, coverage and dependency graph before this implementation plan or Jetty TDD.

Expected size: roughly 70–100 runtime lines plus several hundred necessary test lines across the three named
proof files. Root explicitly approved this size and cohesion: the specific approved Stack's one-complete-feature,
all-safety-before-support requirement governs the approximate 200-line guideline. Do not merge dispatch support
before execution/framing/cleanup proof, or split it merely to reduce the displayed test count.

This plan is committed before test/source implementation. No new semantic/API/spec change is introduced by its
size record. No Doorlist pins/adoption, library release or parent-completion credit follows this plan.

## Exact runtime files

- backends/jetty/pelican-jetty/src/main/kotlin/io/github/matthewjones372/pelican/jetty/Interpreter.kt
- backends/jetty/pelican-jetty/src/main/kotlin/io/github/matthewjones372/pelican/jetty/Responses.kt
- backends/jetty/pelican-jetty/src/main/kotlin/io/github/matthewjones372/pelican/jetty/Server.kt
- backends/jetty/pelican-test-jetty/src/main/kotlin/io/github/matthewjones372/pelican/test/jetty/InMemory.kt
- backends/jetty/pelican-jetty-mcp/src/main/kotlin/io/github/matthewjones372/pelican/jetty/mcp/Mcp.kt
- backends/jetty/pelican-jetty-docs/src/main/kotlin/io/github/matthewjones372/pelican/jetty/docs/Docs.kt (combined handler only)
- docs/reference.md (unreleased construction/support boundary and Doorlist custom/docs adoption tail)

No UnreadBodies, parser, matcher, caller, decoder, filter/idempotency, observer/renderer, lifecycle, executor,
connector, transport/dependency, API constructor or unsupported backend implementation changes.

## Native dispatch and response shape

Remove only native Jetty toHandler's unsupported guard when the complete implementation/tests land together.
Keep route's native find, routing/preflight/decline, malformed-literal attribution and CORS handling unchanged.
Keep absent-hook capture decoding in route before invoke, preserving its order and execution choices exactly.
Carry the already-resolved native routed path into private invoke/invokeHere; do not rematch/reparse it.
For opted-in requests, defer only typed capture decoding into existing invokeHere's native failure boundary:
selected admission once, then native decodeCaller, then index.decodeCaptures, negotiation/plain inputs/body and
existing handlerFor chain. Existing Params/header setup is native metadata only; no application decode occurs there.
Explicit Refuse short-circuits before caller, captures, negotiation, body, filter, reservation and handler.

Admission is synchronous user code that may block. Include opted-in matched endpoints in the existing blocking set
so existing Handlers.mayBlock owns dispatch before callback. Keep onDispatcher/native blocking, virtual/custom
handler policies, resumingHere and native handler hops; add no executor/retry/context wrapper. Callback defects,
CancellationException and InterruptedException follow existing errorResponse/reporting once with selected attribution.

Add only a tiny Refuse-to-Strict adapter: supplied status, body.bytes and exact body.mediaType (including parameters),
null body -> empty bytes/null media type, and supplied headers. Do not route explicit decisions through renderError,
contentTypeOf normalization, a RefusalReason or a new observer event. Strict's native write and existing outer
CORS/global response-header policy apply. Core currently preserves supplied mediaType directly; keep parity.

Keep callback.afterReadingRest and UnreadBodies.kt unchanged: reply written before unread bytes are consumed,
native demand-driven drain bounded at its existing 1 MiB plus the read chunk, then Jetty owns closure. No application
body decoding/opening on Refuse, no unbounded drain or new timeout. Existing framing/body limits remain native.

## Owned/default/custom construction

In Server.kt and test-jetty InMemory.kt use independent private identity-sentinel callback values as default
arguments. Retain each function's existing public/default-mask JVM descriptor and parameter/default order.
Before any callback/pool/Server/connector/transport construction, reject non-null admission with any explicit
custom callback. For the private default sentinel construct native toHandler using the caller's Handlers setting;
for absent admission preserve explicit callbacks and all existing default behavior. No public trusted-marker API.

Retain standalone/direct MCP guards. Add first guard to handlerWithMcp BEFORE toHandler/codecs; no MCP admission.
Add first guard to handlerWithDocs BEFORE toHandler/codecs; startWithDocs remains an explicit custom owned callback
and fails before callback/resources. Standalone docsHandler remains unchanged (serves no described operation).
Document these fail-closed custom/docs paths and any concrete Doorlist composition adoption tail. Native default
owned paths and direct toHandler/manual native construction are supported only by the complete feature.
Do not add HTTP/2 server/transport dependencies; current native factories configure HTTP/1 only.

## Exact tests and proof

New files under backends/jetty/pelican-jetty/src/test/kotlin/io/github/matthewjones372/pelican/jetty/:
- AdmissionJettyTest.kt: actual native LocalConnector/HttpTester for dispatch/headers/framing/refusal/error/limits.
- AdmissionJettyExecutionTest.kt: existing Handlers policies and gated selector-free callback execution.
- AdmissionJettyCleanupTest.kt: staged LocalConnector input and native request/chunk/callback observation for cleanup.
Existing example/src/test/kotlin/example/backends/AdmissionConfigurationTest.kt gains default owned support proof,
removes only obsolete native-Jetty unsupported row, and retains custom/unsupported/MCP cases plus direct combined
docs/startWithDocs rejection. Existing all-backend/compiled-callsite tests remain intact; no new test dependency.

Required cases:
1. Reject beats malformed UUID/query/JSON, missing credentials, tenant/quota/reservation/filter/handler work;
   callback exactly once on selected identity. Continue runs caller before typed decoding, compared with native
   absent-hook order. Successful keyed call preserves native sequence/count, including Idempotency.subjectOf.
2. Hidden, raw request and SSE descriptions are included. Refuse starts no application body decoding or handler stream;
   Continue retains their native supported behavior and strict/body limits. Unmatched/method/preflight paths do
   not fabricate admission. No new CORS/default-header or unmatched-routing policy.
3. Exact refusal bytes/status/media parameters/duplicate supplied headers/null-body behavior; configured response
   headers still apply, explicit refusal never emits RefusalObserver or callback-side traffic telemetry.
4. Repeated/case-varied generic fields arrive completely/in order, immutable snapshots remain stable; HTTP/1
   duplicate Host, case-varied duplicate Host, absolute authority mismatch and ambiguous framing retain native
   transport rejection before callback. No new parser. A read-only Jetty 12.1.13 probe already observed Host 400 responses.
5. Compare callback-thrown cancellation/interruption/Error to equivalent native caller failures for status/body
   redaction/endpoint attribution and original Throwable reporting exactly once; handler never runs afterward.
6. Callback executes under each existing documented Handlers policy. Gated blocking admission leaves a 404 and a
   separate asynchronous/Continue endpoint answerable on the existing single-selector native setup. Gates release
   in finally; no sleeps or elapsed upper-bound performance claim, no extra application executor.
7. Refusal is written while chunked body is still arriving; completion then permits a second request on the same
   native connection. Wrapped native Request/Content.Chunk/Callback observations prove each read chunk released
   once, callback completion once, bounded partial drain beyond 1 MiB, and failure/closed-input cleanup. Preserve
   framing protections; no callback/handler retries or introduced resource leak. Prefer LocalEndPoint staged input, no new port
   except where actual selector/native start behavior is the test subject.
8. Poison codec/custom callback tests prove admitted explicit custom/docs/MCP construction rejects before work;
   default start and owned inMemory actually apply admission and forward the supplied Handlers policy. Absent-hook factories/Handlers and every existing
   public/default-mask descriptor remain compatible, with frozen core consumers and existing backend callsites.

## Execution and integration

Write failing tests first; distinguish current unsupported-construction RED from actual missing-dispatch RED.
Keep one Gradle process in the checkout. Finish all safety cases before one stable final formatting/full build;
focused tests plus unchanged frozen consumers, all affected wrappers/backend tests, complete build/API/analysis/
coverage checks and independent exact-source review precede normal push/PR. Required exact-head/current-base
CI then fresh guarded CTRL-INTEGRATION/expected-head merge and all exact-main terminal checks precede support credit.
Keep I01 incomplete; no Doorlist pins/adoption/release, no new generic framework, no full-traffic accounting claim.
O02/I01 final-response telemetry remains a prelaunch obligation; the measured core streaming ownership gap remains
a separate reviewed follow-up. If a native baseline exposes another defect, record it and obtain review before
expanding this feature; do not quietly change existing ownership, cleanup or authentication counts.

## Closed-input fixture precondition

At reviewed head `b89dceecc7c8e8b1b303f45b667e43867cd905da`, PR CI 41138923095 and push CI 41138907715
failed their JDK 21 jobs on the same CLOSED rows for both native caller refusal and admission: completion
occurred but no failed read chunk was observed. JDK 25 passed. The earlier local warm pass does not establish
the complete CI matrix. Running the original four EOF/CLOSED rows once on macOS Temurin 21.0.9 also passed;
that does not reproduce Linux CI's Temurin 21.0.13 or confirm an original race cause.

Checked Jetty 12.1.13 source: HttpChannelState.java:1087-1130 registers `_onContentAvailable` before
`stream.demand()`. Its onFailure:512-550 arranges a failed read chunk and invokes registered demand, and
separately fails a pending write. Pelican UnreadBodies.kt:22-24 forwards a failed write without entering
Drain; a successful write enters Drain, which releases each chunk before registering its next demand.
The original fixture had not proved which of these paths it selected before closing the endpoint.

Root independently approved a fixture-only stronger precondition: feed one actual three-byte nonterminal
chunk, await native demand registration after observing those bytes, assert bytes=3 and completion=0, then
inject EOF/close. The wrapper signals only after `super.demand` returns. Keep the failed-chunk, once-only
completion, every observed chunk's single release, zero handler and closed-connection assertions unchanged.
No sleep, elapsed performance budget, native cleanup/runtime change or assertion suppression is introduced.

With that precondition, the same 73 focused cases pass on local Temurin 21.0.9 and 25.0.4.1, zero failures,
errors or skips. Fresh exact-head CI remains mandatory; these local results do not replace it.
