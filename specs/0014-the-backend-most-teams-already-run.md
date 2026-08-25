# 0014 — The backend most teams already run

## Problem

Three interpreters exist — Pekko, http4k, Ktor — and `docs/choosing.md` argues
Pelican against springdoc for a reader who is already on Spring Boot. That
reader cannot act on the argument. There is no Spring interpreter, so the
choice on offer is "replace your web stack", which is not a choice a team with
a running service makes for a documentation improvement.

Spring Boot is the largest Kotlin/JVM server audience and the one place the
library-not-framework position costs nothing to hold: `RouterFunction` is a
value registered as a bean. Pelican does not own `main`, does not replace the
`DispatcherServlet`, and composes with routes the service already has. Every
SPI a backend needs is already public — `Api.handlerFor`, `Codecs`,
`renderError`, `Api.corsPolicy`, and `PathSpec.template`, which already emits
`/users/{userId}`: exactly what `PathPatternParser` and Spring Security's
`requestMatchers` read.

`pelican-http4k` is 985 lines against that SPI. This is that shape a fourth
time.

## Not doing

- **No change to `pelican-core`, and none to the other three backends.** The
  shared pipeline — `decodePlainInputs`, `refuseIfOversize`, `orderedEndpoints`,
  `EndpointCodecs`, the CORS folding — is private in each interpreter and gets
  copied a fourth time. Named cost: a decode fix now lands in four places
  instead of three. `TODO.md` proposes hoisting it into core; that is a
  separate spec and does not block this one.
- **No JSON library.** `pelican-spring` depends on core and `spring-webmvc`,
  nothing else. Bodies go through whichever `Codecs` the `Api` was built with,
  as on every other backend. `NoOtherDependenciesTest` states it.
- **No Jackson 3.** `pelican-jackson` stays on Jackson 2 (`com.fasterxml`),
  which is Boot 3. Boot 4 moved the default to `tools.jackson`; that is a new
  leaf module and a separate spec, not an edit here.
- **No Boot starter and no auto-configuration.** A `@Bean` returning
  `api.toRouterFunction()` is one line the user writes. A starter is a second
  module with a Boot dependency and can follow once the interpreter exists.
- **No WebFlux.** `WebMvc.fn` first; virtual threads on Boot 3.2+ make the
  blocking one fine, and `WebFlux.fn`'s `RouterFunctions.route()` is close
  enough that the second is cheap later.
- **No springdoc interop.** `pelican-spring-docs` serves the document Pelican
  generates, on the same terms as `pelican-http4k-docs`. Pointing an existing
  Swagger UI at it is configuration, not code.

## Shape

Same two calls every backend has, plus the one Spring wants:

```kotlin
val routes = listOf(
    greet handledNow { (who, shout) -> greetingOf(who, shout) },
    countdown streamedNow { start -> (start downTo 1).asSequence().map(::tick) },
)

@Bean
fun pelicanRoutes(): RouterFunction<ServerResponse> =
    Api(routes, codecs = JacksonCodecs).toRouterFunction()
```

`streamedNow` takes `(I) -> Sequence<T>`, as on http4k: Spring MVC answers on
the calling thread. `toRouterFunction()` composes with `RouterFunctions.route()`
so a service keeps its own controllers.

## Why this shape

`RouterFunction<ServerResponse>` rather than a `HandlerMapping` or a registered
`@RestController`: it is a value, it is what `MountedAlongsideTest` needs, and
it keeps the description as the only source of truth. The alternative — generate
annotated controllers so springdoc reads them — reintroduces the second
description this library exists to delete. Not recommended.

MVC before WebFlux because the audience argument is about teams who already run
Spring, and most of them run MVC. Answering with `ServerResponse` on the
calling thread also means the http4k code is the closer model of the two that
exist, which is where the 985-line estimate comes from.

## Stack

- [ ] **`spec-0014-spring-routes`** — `pelican-spring` module, `Interpreter.kt`: route registration, method and path matching, plain input decode. No bodies.
      Done when: a two-endpoint API answers through a `RouterFunction`, and `NoOtherDependenciesTest` proves core + spring-webmvc + slf4j and nothing else.
- [ ] **`spec-0014-spring-bodies`** — `Handlers.kt` binders (`handledNow`, `handledOrFail`, `handledOneOf`), body read and write, `Responses.kt` for status, headers and `RenderedError`.
      Done when: a declared failure answers its declared status with the same body the other three render.
- [ ] **`spec-0014-spring-streaming`** — `streamedNow` over `Sequence`, SSE, and multipart uploads.
      Done when: `SseKeepAliveTest` and `CookiesFormsAndUploadsTest` pass against Spring.
- [ ] **`spec-0014-spring-cors-filters`** — `corsPolicy` folding, `Filter` application, oversize refusal.
      Done when: `CorsTest`, `FiltersAndHeadersTest` and the 413 case pass.
- [ ] **`spec-0014-spring-parity`** — `OnSpring` in `example/backends`, one entry in `allBackends`.
      Done when: the whole `example` suite runs four backends green with no new assertions.
- [ ] **`spec-0014-spring-docs`** — `pelican-spring-docs` and `pelican-test-spring`, plus `docs/modules.md`, `docs/choosing.md`, README and CHANGELOG.
      Done when: `docsRoutes` serves the document and the UI, the in-memory transport works, and `docs/modules.md`'s dependency claims are tests.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Spring version floor — Framework 6.2 (Boot 3) or 7.0 (Boot 4)? Recommend
   6.2: it is the larger installed base and 7 reads it. Pin it with the note
   `pelican-http4k/build.gradle.kts` carries about Kotlin metadata.
2. How do `example` and the tests bind a `RouterFunction` to a port without
   putting Boot in the library? Recommend embedded Tomcat in `example` and test
   scope only, so `pelican-spring` stays at `spring-webmvc`.
3. Error body — `ProblemDetail` (RFC 9457, what Spring answers natively) or the
   shape the other three render? Recommend the shared shape: `AllBackendsTest`
   asserts one body across backends and parity is the point. `ProblemDetail` as
   an opt-in `Api` setting, later, for all four at once.
4. Does `PelicanServer` and `Api.start(port)` exist here at all? Recommend no —
   a Spring app has its own `main`, and `toRouterFunction()` is the whole
   surface. `Running` in the example wraps the embedded server from question 2.
5. `pelican-test-spring` — invoke the `RouterFunction` directly with a mock
   request, or run a real port? Recommend direct invocation, matching
   `pelican-test-http4k`'s 56-line `InMemory.kt`.
6. Where does the fourth copy of the shared pipeline live — duplicated in
   `pelican-spring`, or is the hoist worth doing first after all? Recommend
   duplicating, per the constraint above, and opening the hoist spec at the same
   time so the debt is written down rather than absorbed.
