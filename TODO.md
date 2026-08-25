# TODO

Who is in a position to use the library, and through which HTTP stacks.

This is not the [roadmap](docs/roadmap.md), which argues what Pelican should
*do* next. Everything here is about reach: the library is published, tested and
documented, and has no users, and no roadmap item fixes that.

`[ ]` open, `[x]` done, `[!]` needs the repository owner — a settings change or
a public post that nobody else can make.

---

## Findability

Free, and mostly absent today.

- [!] **Topics.** None set, so the repository is on no topic page.
      ```bash
      gh repo edit --add-topic kotlin --add-topic openapi --add-topic http4k --add-topic ktor --add-topic pekko --add-topic type-safe --add-topic api-first --add-topic rest-api --add-topic kotlin-library --add-topic openapi-generator
      ```
- [!] **Homepage URL.** Empty. Point it at the docs site.
      ```bash
      gh repo edit --homepage https://matthewjones372.github.io/pelican/
      ```
- [!] **Social preview.** `docs/assets/social-preview.png`, uploaded under
      Settings → General → Social preview. GitHub does not read it from the
      repository. Regenerate with `python3 docs/assets/social-preview.py`.
- [!] **A GitHub release per Central version.** `v0.1.0` is tagged and
      published; the releases page is empty. The feed reaches watchers and
      Kotlin Weekly scrapes it.
      ```bash
      gh release create v0.1.0 --title "0.1.0" --notes-from-tag
      ```
- [x] **`CHANGELOG.md`.** A 0.x library promising breaking changes needs one.

## Machine-readable

- [x] **`AGENTS.md`** carries the layering, the testing order and the gates,
      not only the comment rules.
- [x] **`CLAUDE.md`** points at it.
- [x] **`docs/cookbook.md`** — complete recipes. Agents copy whole working
      examples, not fragments.
- [x] **`llms.txt`**, at the docs-site root.
- [x] **Dokka fills the javadoc jar.** It was `JavadocJar.Empty()`, so
      javadoc.io showed nothing and the KDoc was unreadable without a clone.
- [ ] **Error message quality.** A typed DSL suits coding agents because they
      compile, read the error and retry. Audit what the common mistakes
      actually print — an inference failure that names neither the endpoint nor
      the declared type costs a retry loop its convergence.

## The docs site

- [x] GitHub Pages, built from a staging directory that mirrors the repository
      layout, so every relative link in `docs/` resolves unchanged in both
      places.
- [!] Settings → Pages → source: **GitHub Actions**. The workflow cannot enable
      itself.

## Announcing — after the above

- [!] Kotlin Weekly.
- [!] r/Kotlin; Kotlin Slack `#feed` and `#server`.
- [!] The http4k, Ktor and Pekko communities. Pelican is an add-on to their
      stacks rather than a competitor, which makes them the highest-conversion
      audience available.
- [!] `KotlinBy/awesome-kotlin`.
- [ ] A post arguing the idea rather than announcing a library.
      `docs/choosing.md` is most of its draft.

---

## Servers

Three interpreters exist. The roadmap is right that a fourth proves nothing
further about the abstraction; this list is about audience, which is a separate
argument.

Cost of one, measured against the three that exist: roughly 1,000 lines across
`Interpreter.kt`, `Handlers.kt`, `Responses.kt` and `Server.kt`, plus a `-docs`
module and a `-test-` transport for parity. Nothing in `pelican-core` changes.
The SPI a backend needs — `Api.handlerFor`, `Params`, `Codecs`, `renderError`,
`corsPolicy`, `pathSpec.template` — is already public.

1. **Spring Boot, WebMVC functional.** The largest Kotlin/JVM audience, and the
   reason most teams cannot use this library at all. `RouterFunction` is a
   value registered as a bean, so it does not cost the library-not-framework
   position. MVC before WebFlux; virtual threads on Boot 3.2+ make the blocking
   one fine. It replaces springdoc rather than coexisting with it.
2. **Servlet, `jakarta.servlet`.** The most reach per line: Jetty, Tomcat,
   Undertow, Dropwizard, Javalin's substrate, and Spring MVC as a `Filter`.
3. **Vert.x Web.** A clean `Route` mapping and coroutines through
   `vertx-lang-kotlin-coroutines`.

Not worth it: Micronaut and Quarkus (compile-time DI against runtime route
registration, and a mostly-Java audience), Javalin (small, and the servlet
interpreter half-covers it), raw Netty.

- [ ] **Hoist the shared pipeline into core before backend four.**
      `decodePlainInputs`, `refuseIfOversize`, `orderedEndpoints`,
      `EndpointCodecs` and the CORS folding are private in all three
      interpreters. A decode fix already has to land three times. Hoisting is
      core-side only and turns each new backend into roughly 300 lines of
      genuinely backend-specific work: route registration, body reading,
      response writing, streaming, async model.

## Clients

`ClientTransport` has landed, with `pelican-client-java` over the JDK's own
`HttpClient` and `pelican-client-pekko` beside it, plus the `suspend` surface
and the retry policy. Two adapters are left, and one of them carries most of
the remaining audience:

- [ ] **`pelican-client-okhttp`.** `java.net.http` does not exist on Android, so
      every Android caller of a Pelican-described API is excluded today. OkHttp
      is Android's default, and the adapter is small now the SPI exists. This
      is the largest single audience gap on the client side.
- [ ] **`pelican-client-ktor`.** A service already tuning one Ktor engine should
      not acquire a second HTTP stack to call a generated client. Also the
      route to Kotlin Multiplatform, and so to iOS and JS callers.

A server interpreter wins a backend team. A client adapter wins an
organisation: the backend describes the API and mobile consumes the same
description.
