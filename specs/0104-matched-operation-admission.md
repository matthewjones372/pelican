# 0104 - Matched-operation admission before decoding

Approved under delegated root technical review on 30 October 2026, after the all-values header clarification.
Root reviewed initial draft `5c04a618` and compatibility refinement `c05eb2f`; the initial five-entry stack is
expanded to the approved six entries below. This is delegated review, not a claim of human editing. Each revised
entry waits for this approved spec on main and successful main CI before implementation.
Source: main `ac81a0f15e5c1abd231ae87ff5e3bb36f6e7b3f8`, published 1.2.0; Doorlist I01 reserves this need.

## Problem

A multi-audience service must reject a matched operation on the wrong host before authenticating its caller or
decoding typed captures, fields or bodies. Filters run too late: core in-memory and Jetty decode first, then call
handlerFor. RouteIndex.match also decodes captures before the caller. A service otherwise repeats routing or caller
logic, and malformed input can win over its access refusal. Existing TenantResolver is not this operation/host gate.

## Not doing

Host/tenant policy, a directory, new callers, URL matching in services, body parsing in admission, changes to 0095
tenant/session scoping or 0103 caller addresses, new dependencies, background work or an automatic library release.

## Shape

Proposed public shape in the existing core package, not APIs available in 1.2.0:

```kotlin
fun interface OperationAdmission { fun check(request: AdmissionRequest): AdmissionDecision }
class AdmissionRequest internal constructor(
    val endpoint: Endpoint<*, *>, private val fieldLines: (String) -> List<String>,
) {
    fun header(name: String): String?
    fun headers(name: String): List<String>
}
sealed interface AdmissionDecision {
    data object Continue : AdmissionDecision
    class Refuse(val status: Int, val body: RefusalBody? = null,
                 headers: List<Pair<String, String>> = emptyList()) : AdmissionDecision {
        val headers: List<Pair<String, String>>
    }
}
```

Add member `ApiBuilder.admission(check: OperationAdmission): Unit` and read-only `Api.admission: OperationAdmission?`.
One optional callback per Api; a second configuration is rejected. Request views are core-created, expose the
library-selected endpoint and native read-only header lookup, never raw URL, decoded inputs, caller bag or backend
request. Refuse is a 4xx/5xx status with optional existing RefusalBody and defensively copied, validated headers;
null body means no body, not a rendered generic error. Configured codecs produce any promised JSON error bytes.

`headers(name)` returns an immutable snapshot of every field line in arrival order with native case-insensitive
lookup, reusing RequestView.headers semantics or the in-memory ClientRequest's complete field list. No joining,
deduplication or first-value collapse. `header(name)` remains a documented first-value convenience; authority policy
must inspect all values. Credentials.header and ClientRequest.header alone cannot supply this view. Preserve native
HTTP/1 and supported HTTP/2 authority mapping/ambiguity rejection; never invent a second parser or expose raw URI.

For an opted-in Api, native untyped selection precedes admission; Continue runs the native caller/tenant checks
before typed captures/plain/body decoding, filters, idempotency and the handler. Reject invokes none of those and
does not refresh a session. Existing HTTP framing, ambiguous-authority parsing and body-size protections remain:
transport rejection can occur first, and refusal never causes application decoding or an unbounded body drain.
Use the existing execution context, interruption, cleanup and failure renderer; no extra executor or retry.
Cancellation and unexpected defects never become Continue or Refuse; native cancellation/defect reporting applies.

Absent admission keeps existing runtime ordering, contracts and source/binary entry points unchanged. Add Api/DSL
configuration with compatible constructor/compiled-consumer bridges, not just Kotlin default parameters. A backend
without this feature must reject non-null admission at construction, never silently ignore it. Core in-memory and
Jetty support it; admitted in-memory raw-body calls keep their existing unsupported-call rule.

## Why this shape

The interpreter already owns matching, credentials and decoding; one neutral hook keeps precedence shared. An
ordinary filter is after decoding. An application router or fake caller duplicates authority. Recommend the small
hook; reuse native response/refusal handling and keep host decisions in the service. Other backends can opt in later.

## Stack

- [ ] **`codex/i01-admission-contract`**: neutral values/validation, no callable dispatch hook yet; API tests pass.
- [ ] **`codex/i01-admission-compatibility`**: restore released 1.2.0's 21-parameter full/default-mask Api
      constructors beside current main's 25-parameter ones; run frozen consumers compiled against each original
      baseline. Keep `api`/`api$default`, ApiBuilder construction/build and every existing DSL entry point unchanged.
- [ ] **`codex/i01-admission-config`**: optional Api/Builder setting and binary bridges; unsupported interpreters
      reject non-null admission. The new 26-parameter primary retains hidden 25/21-parameter secondary constructors
      with their original defaults, delegating admission=null; hidden bridges avoid Kotlin overload ambiguity.
      Before constructing codecs, handlers or owned runtime resources, reject configured admission in:
      `InMemoryClientTransport`; Jetty `toHandler`/`start` and owned `inMemory` (custom callbacks included);
      Pekko `toRoute`, owned `start`/`inMemory` and borrowed `bind`; Gannet `toHandler`/`start`.
      The same unsupported-interpreter rule covers MCP's direct dispatch:
      add the first guard in `integrations/pelican-mcp/src/main/kotlin/io/github/matthewjones372/pelican/mcp/McpDispatch.kt`
      before filtering tools, constructing schemas/codecs or calling `handlerFor`.
      Add direct `mcpDispatch` and HTTP MCP wrapper rejection cases beside the shared backend configuration tests.
      No MCP admission, dependency, transport or absent-hook behaviour changes.
- [ ] **`codex/i01-admission-core`**: core in-memory ordering, native selection and refusal rendering; no duplicate
      matcher; gated decoders/callers/reservation/handler prove rejection runs none and callback runs once.
- [ ] **`codex/i01-admission-jetty`**: same contract on Jetty; parity tests for captures, JSON, hidden/raw/SSE routes,
      all header values/body-free replies, interruption/cancellation, defects, limits and once-only cleanup.
- [ ] **`codex/i01-admission-docs`**: reference/cookbook/changelog, API/binary and absent-hook backend proof; mark
      unreleased availability. Each entry stays near 200 changed lines; split before code if its concrete plan exceeds it.

Compatibility fixtures contain full, named/default and factory/DSL call sites compiled against the published
1.2.0 artifact and a jar built from exact main `b9632bd` source before changes. Commit source, generated bytecode, hashes
and reproducible compiler commands; tests execute frozen bytecode against the candidate, including full-setting
round trips. Constructor reflection alone is not proof; no compiler/runtime dependency is added to core.
During configuration, core in-memory, Jetty, Pekko and Gannet construction all reject a configured hook before
codec/handler construction. Core and Jetty remove that guard only when their reviewed dispatch entries support it.

## Acceptance

Reject with malformed UUID/JSON or missing/invalid credentials before application decoding/auth/reservation/work;
Continue retains native access-first failures; no-header/body/frame limits weaken. Compare synchronous cancellation
and Error propagation with the native failure boundary; no swallowed defect, duplicate report or leaked raw data.
Compile old consumers and run absent-hook contracts across all supported backends; core/Jetty admitted/refused parity.
Pin duplicate Host field lines, case variants, and Host/:authority ambiguity under native HTTP/1 and supported
HTTP/2 semantics: either native transport refusal or complete values reach admission, never an admissible first value.
Prove returned header lists cannot be mutated and later source-list mutation cannot change their earlier contents.

```bash
./gradlew :pelican-core:test :pelican-jetty:test
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Resolved under delegated root review: real ApiBuilder member/read-only Api setting with binary bridges; existing
RefusalBody/native status/header validation; native cancellation/defect propagation; immutable all-values headers.
Concrete source and deterministic tests remain independently reviewed per stack entry. No unresolved policy choice
grants permission to change absent-hook behaviour, weaken transport parsing, or guess a service's authority rules.
