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
- [!] **Homepage URL.** Empty. Point it at the Maven Central listing, since
      there is no docs site and the repository is its own front page.
      ```bash
      gh repo edit --homepage https://central.sonatype.com/artifact/io.github.matthewjones372/pelican-core
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
- [x] **`llms.txt`**, pointing at the files in this repository.
- [x] **Dokka fills the javadoc jar.** It was `JavadocJar.Empty()`, so
      javadoc.io showed nothing and the KDoc was unreadable without a clone.
- [ ] **Error message quality.** A typed DSL suits coding agents because they
      compile, read the error and retry. Audit what the common mistakes
      actually print — an inference failure that names neither the endpoint nor
      the declared type costs a retry loop its convergence.

## A docs site — not now

Tried and removed. GitHub renders this repository's markdown better than a
stock Jekyll build does: the front page arrived on Pages as one paragraph of
literal source, because kramdown does not parse markdown inside the README's
centring `<div>` and GitHub's renderer does. A `github.io` subdomain also
ranks below `github.com` for the searches that matter, and the site carried no
theme, no navigation and no search — everything that would make a site beat a
repository.

Worth revisiting only as a real one: MkDocs Material or Dokka-integrated docs
with sidebar navigation, search and versioning, once there are users asking
questions the README does not answer.

The one thing GitHub cannot render is API documentation, and that ships through
javadoc.io from the Dokka javadoc jar, which needs no site.

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
