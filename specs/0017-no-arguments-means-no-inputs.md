# 0017 — No arguments means no inputs

## Problem

`endpoint(a)` declares one input, `endpoint(a, b)` two, and `endpoint(a, b, c)`
three. `endpoint { }` declares the whole `Params` bag:

```kotlin
fun <R> endpoint(block: EndpointBuilder.() -> Output<R>): Endpoint<Params, R>
```

It is the lens form — the escape hatch past the sixth arity, where the handler
reads inputs by key and gives up the compile-time guarantee. It is not zero
inputs, and it took the spelling that zero inputs should have had. `noInputs`
exists to fill the hole that left:

```kotlin
val motd = endpoint(noInputs) { get("motd"); text() }     // no inputs
val motd = endpoint { get("motd"); text() }               // a Params bag, ignored
```

Both compile. The second is what everybody writes, and it hands the handler a
`Params` it does not want and cannot usefully read — a signature that says
there is something to read when there is nothing.

Counted across this repository, of the endpoints written with the zero-argument
form, roughly two thirds declare no inputs at all. They are all taking the
escape hatch by accident.

The lens form already has a spelling of its own. `lensInputs` is public, and
`endpoint(lensInputs) { }` compiles today and means exactly what it says.

## Not doing

- No change to the six arities, or to `Inputs<I>`.
- No deprecation cycle. The library is 0.x, the change is a compile error rather
  than a behaviour change, and a deprecation that both spellings survive is how
  a codebase ends up with both forever.
- Not removing `lensInputs`. It becomes the only way to ask for the bag, which
  is the point.

## Shape

The zero-argument overload means what its arity says, and the lens form is
asked for by name:

```kotlin
fun <R> endpoint(block: EndpointBuilder.() -> Output<R>): Endpoint<Unit, R>

val motd = endpoint { get("motd"); text() }                 // Endpoint<Unit, String>
motd handledNow { "Be excellent to each other." }

val search = endpoint(lensInputs) {                          // Endpoint<Params, Page>
    get("search")
    query(term, size, cursor, sort, after, before, facet)     // past the sixth
    json<Page>()
}
search handledNow { p -> pageOf(p[term], p[size]) }
```

`noInputs` goes: `endpoint { }` is what it was for.

## Why this shape

The alternative is leaving it and documenting `noInputs` better. That keeps two
spellings for zero inputs, one of which is wrong in a way nothing catches — the
handler simply receives a parameter it ignores — and keeps the arity ladder
reading `1, 2, 3, …, and separately, all of them`.

Making the change loud is what makes it safe. Every affected call site is a
handler whose parameter type moves from `Params` to `Unit`, which does not
compile; there is no shape that silently keeps working and means something else.

## Stack

- [x] **`spec-0017-zero-is-zero`** — the overload returns `Endpoint<Unit, R>`; `noInputs` removed; every in-repo lens user moved to `endpoint(lensInputs)`.
      Done when: `./gradlew build` is green, no `noInputs` remains, and an endpoint declaring inputs in its block reads them through a `Params` it asked for by name. Landed in [#61](https://github.com/matthewjones372/pelican/pull/61).
- [x] **`spec-0017-docs`** — `docs/reference.md`'s input section, the cookbook's lens recipe, and the "more than six typed inputs" refusal.
      Done when: no page shows `endpoint(noInputs)`, and the lens form is introduced as what it is rather than as the default. Landed in [#62](https://github.com/matthewjones372/pelican/pull/62).

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Is `lensInputs` the right name now that it is the only spelling? `byKey` or
   `allInputs` reads better at a call site. Recommend keeping `lensInputs` in
   this spec and renaming in one of its own if it still grates — two changes to
   one line is one change too many to review at once.
2. Should `endpoint { }` with inputs declared in the block be refused, now that
   it means zero? It is describable and the values are decoded, they are simply
   unreadable by the handler. Recommend refusing it in `validate`: an endpoint
   that decodes a query parameter nobody can read is the same mistake
   `(inPath - declared)` already refuses for a path capture.
3. Does the importer emit the zero-argument form for operations with no
   parameters? Recommend checking in the first entry; if it emits
   `endpoint(noInputs)` the generator changes with the API.
