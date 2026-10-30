# 0104 - Matched-operation admission before decoding

Draft for delegated technical peer review; no implementation before reviewed spec lands on main.
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
    val endpoint: Endpoint<*, *>, private val credentials: Credentials,
) {
    fun header(name: String): String?
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
- [ ] **`codex/i01-admission-config`**: optional Api/Builder setting and binary bridges; unsupported interpreters
      reject non-null admission, old compiled consumers and absent-hook behaviour remain unchanged.
- [ ] **`codex/i01-admission-core`**: core in-memory ordering, native selection and refusal rendering; no duplicate
      matcher; gated decoders/callers/reservation/handler prove rejection runs none and callback runs once.
- [ ] **`codex/i01-admission-jetty`**: same contract on Jetty; parity tests for captures, JSON, hidden/raw/SSE routes,
      headers/body-free replies, interruption/cancellation, defects, transport limits and once-only cleanup.
- [ ] **`codex/i01-admission-docs`**: reference/cookbook/changelog, API/binary and absent-hook backend proof; mark
      unreleased availability. Each entry stays near 200 changed lines; split before code if its concrete plan exceeds it.

## Acceptance

Reject with malformed UUID/JSON or missing/invalid credentials before application decoding/auth/reservation/work;
Continue retains native access-first failures; no-header/body/frame limits weaken. Compare synchronous cancellation
and Error propagation with the native failure boundary; no swallowed defect, duplicate report or leaked raw data.
Compile old consumers and run absent-hook contracts across all supported backends; core/Jetty admitted/refused parity.

```bash
./gradlew :pelican-core:test :pelican-jetty:test
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. Configuration member or extension? Recommend a real ApiBuilder member and read-only Api property, with explicit
   binary bridges and no changed old constructor entry points; the sketch denotes that public surface.
2. Refusal body model? Recommend reusing RefusalBody plus native header/status validation; do not add a second JSON
   renderer. Body-free refusal and declared JSON bytes need core/Jetty equality tests before implementation.
3. Callback defects/cancellation? Recommend native failure/cancellation propagation, never a declared refusal;
   pin both causes and no later stage/extra cleanup with deterministic tests before accepting the API.
