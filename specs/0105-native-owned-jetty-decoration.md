# 0105 - Native-owned Jetty decoration before binding

Approved under delegated I01 root/KMS technical review on 31 October 2026; no implementation/pin/release yet.
Reviewed private draft SHA2563221489f0bfff8fc2caff90d49193ec8874f053e6b91cd9d9c866505fe92e26a.
Baseline main125b074ac4372be73ef38278f64863698f51d0de; number0105 rechecked free, open PRs362/338 dependency-only.

## Problem
Public Api.toHandler + Jetty Server/ServerConnector + PELICAN_URI_COMPLIANCE/Unanswered already supports
manual composition; standalone Api.docsHandler also works. No missing interpreter or universal API need is claimed.
Doorlist's admitted native endpoints need existing edge/limit/static/docs wrappers while retaining PelicanServer
and Pelican's internal pool/connector policy. Manual ownership would require a separately reviewed graph-handle
migration across production/fixtures; explicit Api.start callbacks and handlerWithDocs currently reject admission.

## Not doing
Host/auth policy, a second router/Api, new dependencies/executors/transports, HTTP/2 configuration, MCP admission,
changes to old custom callback/docs guards or old lifecycle behavior, broader core streamed-input ownership fixes,
traffic telemetry, Doorlist wiring/pins, publication or a stable release. Decoration is trusted application composition.

## Shape
New public extension in io.github.matthewjones372.pelican.jetty, existing Server.kt:
```kotlin
fun Api.startDecorated(
    port: Int = 8080,
    host: String = "127.0.0.1",
    handlers: Handlers = Handlers.default,
    decorate: (Handler) -> Handler,
): PelicanServer
```
No decorator default or receiver callback: the caller receives the already constructed native handler.
It may wrap/sequence that handler with non-operation handlers; it must delegate described operations to it.
The owner cannot prove arbitrary application code delegates; this trust contract must be explicit, not a sandbox claim.
Build native toHandler(handlers) EXACTLY ONCE and invoke decorate EXACTLY ONCE before bind/start; attach returned tree.
Reuse existing pelicanPool/pelicanConnector/ConnectorSettings, configured host/port and Unanswered; return the existing
PelicanServer(this, server, host). Native matching/admission/caller/decoding/framing/body/header paths stay untouched.
Immediately after Server acquisition, establish failure cleanup BEFORE connector configuration, codec/native-handler
construction, decoration, tree attachment or start. On any failure/cancellation, stop owned Server; rethrow the exact
original Throwable, with distinct stop failure suppressed. Successful cleanup closes owned listeners; no retry.
A cleanup failure is reported as such; suppressing it is not proof resources were successfully released.
Decorator owns resources it creates until it returns its tree; returned Jetty-managed tree is owned by this server.
Retain existing PelicanServer stop/stopAsync/block/close behavior and native thread policy; promise no stronger stop API.
All old start/start$default, handlerWithDocs/startWithDocs, owned inMemory/custom/MCP descriptors and guards stay intact.
Api/Builder/constructor/default-mask descriptors and ALL existing frozen source/bytecode/golden bytes stay unchanged;
only a NEW compiled fixture is added. No constructor exposure or backend widening.

## Why this shape
Recommended over a Doorlist owner/handle migration: reuse the library's transport lifetime and existing handle consumers.
Alternative supported today: application-owned Server with public native handlers and its own explicit resource scope.
No raw custom callback is reclassified as native; local docs compose via standalone docsHandler after native endpoints.

## Stack
- [ ] `codex/i01-native-owned-jetty-contract`: this spec only, independent review and committed contract before source.
- [ ] `codex/i01-native-owned-jetty`: one complete additive feature, <=200 changed non-generated lines: Server.kt,
      NEW backends/jetty/pelican-jetty/src/test/kotlin/io/github/matthewjones372/pelican/jetty/NativeDecorationTest.kt,
      existing backends/jetty/pelican-jetty/api/pelican-jetty.api; all acceptance below before support merges.
- [ ] `codex/i01-native-owned-jetty-docs`: docs/reference.md, docs/cookbook.md, CHANGELOG.md; explicit-import compiled
      NEW example/src/test/kotlin/example/compiler/NativeDecorationCallSites.kt holds nativeDecorationCallSites
      with a unique nativeDecoration key. Existing example/src/test/kotlin/example/compiler/StillCompilesTest.kt
      explicitly combines frozenCallSites + nativeDecorationCallSites and uses that map for areas/write/report;
      no automatic file discovery is claimed. Existing FrozenCallSites.kt is byte-identical; <=200 lines, unreleased.
If complete feature proof exceeds 200 lines INCLUDING tests, review a concrete split BEFORE source: private owned
assembly without public activation first, then public entry plus complete safety matrix; no partial support claim.

## Acceptance
New tests pin once-only native construction/decoration, unbound server during decoration, same selected native operation,
Continue and exact empty/body Refuse through a real decorated server with outer headers; no caller/body/key/handler on Refuse.
Pin original decorator/codec/start/interrupt failure identity and owned cleanup; distinct cleanup failure is suppressed.
Run native malformed-target/body limits, unread-body bounded release/completion/connection-reuse and request-cancellation
proof unchanged; add a decorated-owner integration row where ownership changes need proof, not a copied body implementation.
Retain absent-hook start/stop/block contracts, all old custom/docs/MCP refusal cases and frozen real-baseline Api consumers;
API dump shows additive new function/default bridge only. Full existing build/checks/coverage and JDK21/25 CI remain required.
```bash
./gradlew spotlessApply build
```

## Open questions
None for this bounded contract: root selected startDecorated and the <=200-line feature/docs entries above.
Current Doorlist strict inputs do not establish broader core Body.Streaming closure; retain that gap and require
its separate proof/fix if future scope promises those owned inputs, not as a prerequisite for absent inputs.
Required Jetty ownership and Refuse final-response telemetry remain I01/O02 obligations; no activation/readiness credit.
