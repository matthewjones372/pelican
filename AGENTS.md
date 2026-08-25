# Working in this repo

## Comments

Comments record what the code cannot: the reason a thing is done the way it is.
They do not restate the code, and they are not essays.

**Write a comment when there is a why.** A surprising API, a constraint from a
dependency, a trade-off that was actually made, a bug that a naive version
reintroduces. One or two sentences.

**Do not write one when there is not.** If the signature and the body already
say it, say nothing.

### Rules

- **KDoc: one line by default.** Two or three only where the reason genuinely
  takes them. Reserve a multi-paragraph block for a decision the reader would
  otherwise undo — there should be very few in a file.
- **No worked examples in KDoc** unless the call is hard to get right from the
  signature. The README and `docs/` carry the tutorial; a code comment is not
  the place to teach the DSL twice.
- **No restating the code.** `/** The status. */ val status: Int` earns
  nothing.
- **No history.** "The alternative was…", "this used to…", "before this it
  was…" belongs in the commit message, not the source. Exception: naming a bug
  the comment exists to stop coming back.
- **No rhetorical framing.** Skip "Note the split of responsibilities", "which
  is the whole point", "and that is the difference that buys". State the fact.
- **Inline `//` notes are for the line below them**, not for paragraphs. If it
  runs past three lines, it is either KDoc or it is too long.

### Shape

```kotlin
// Yes — names a real constraint, once.
/** Pekko's `StatusCodes.get` throws for unregistered codes; `custom` does not. */
private fun statusOf(code: Int): StatusCode = ...

// No — restatement, then an essay about it.
/**
 * Pekko's own status where it knows one, and a custom status where it does not.
 *
 * `StatusCodes.get` is not a lookup: it is `int2StatusCode`, which throws for
 * anything not in the registry. So an endpoint that declared a perfectly legal
 * 419 used to document that status and then answer 500, because the throw
 * landed in the interpreter's own `exceptionally`. ...
 */
```

Same rules in test sources. A test name should carry the claim; the KDoc above
it should not repeat the name in longer words.

## Layout

`pelican-core` depends on the Kotlin standard library and nothing else.
Everything with a third-party type in it is a leaf module: a JSON library, a
server library, the OpenAPI generator, a client transport.

Every dependency claim in `docs/modules.md` is a test.
`NoThirdPartyDependenciesTest` asserts core's runtime classpath. Each backend
asserts the document generator and the other backends are absent.
`pelican-test` asserts no server library and no matcher library.

A dependency added to core is a build failure, not a judgement call. Core
declares an interface; an adapter module carries the library. `Codecs`, the
three interpreters and `ClientTransport` are all that shape.

## Values, errors and effects

Descriptions are values. There is no registry and nothing that has to run at
startup: `routes` is a `List<ServerEndpoint>` that can be split across files or
filtered. Prefer a value to a function when adding a feature.

Errors a caller was promised are values in the return type. `orFail` puts the
declared failure into the endpoint's type and `handledOrFail` takes a handler
returning `Outcome<E, A>`. Throwing is for what nobody declared — a decode
failure, a broken codec, a bug.

Do not wrap a handler in `runCatching` and map the result into a failure. That
produces a second error model beside the declared one. `example/shop` shows the
shape.

`catch (t: Throwable)` belongs in `Interpreter.kt`, `Server.kt` and
`Responses.kt`. detekt permits it nowhere else.

Never add an `else` to a `when` over a sealed type. The missing branch is the
compiler naming a case that needs handling in every interpreter.

Refining a codec must narrow what is accepted *and* reach the document. A
constraint the server enforces and the schema omits is a lie in the contract.

Public API returns read-only types. A mutable accumulator is allowed inside a
builder that freezes it before returning. `FunctionalStyleTest` lists every
file permitted one, each with its reason; a new file needs an entry and a
reason, not a path added to get green.

The handler chain carries `CompletionStage<Any?>`. `suspend` cannot appear in
core — `NoThirdPartyDependenciesTest` would say so.

## Testing

Write the failing test first. Most claims here are claims about agreement
between the route, the document, the test client and the generated client, and
a test written afterwards asserts what the implementation does rather than what
the description promised.

Test names are sentences in backticks. Kotest matchers, JUnit 5, `withClue`
where a bare boolean would not explain itself.

Work out which of these a change can break:

- **Contract tests** through the typed client — `app.call(getUser, 1L)`. No
  path strings, no hand-written JSON.
- **URL pins**, in a separate file, with `shouldBuild`. Behaviour tests should
  survive a rename; the pin should not.
- **Backend parity.** A change to one `Interpreter.kt` usually means the same
  change to the other two. `AllBackendsTest` runs one suite against all three.
- **Golden files.** A moved golden is the test working. Read the diff and
  decide whether the break is intended; do not regenerate for green.
- **Spec quality.** `OpenApiSpecQualityTest` reads emitted documents with a
  parser that did not write them.
- **The round trip.** `ImportedOrdersTest` imports the published document back
  into descriptions and compiles them.

Kover is aggregated across modules with a floor of 80% on `check`.

## Verifying

`./gradlew build` runs tests, detekt and spotless. Run it before saying
anything is done, and quote the result rather than predicting it.

Six gates sit beyond the tests. Each exists because a claim in the reference
manual would otherwise be unverified.

| Gate | Fails when | Not the fix |
|---|---|---|
| detekt | any finding | a suppression with no reason |
| `FunctionalStyleTest` | a new file allocates a mutable collection | an unexplained entry |
| Kover | aggregate line coverage under 80% | lowering the floor |
| `OpenApiSpecQualityTest` | emitted docs fail an independent parser | asserting against the emitter |
| `checkOrdersClient` | the committed example client is stale | editing generated source |
| `ImportedOrdersTest` | the round trip disagrees | special-casing the importer |

Before saying it is done:

- The failing test came first, and fails without the change.
- `./gradlew build` is green, gates included.
- If an interpreter changed, all three did, and parity is asserted.
- If a caller-visible behaviour changed, the document changed with it.
- No new dependency in `pelican-core`.
- `docs/reference.md` reflects the change, or the change is invisible from
  outside.
