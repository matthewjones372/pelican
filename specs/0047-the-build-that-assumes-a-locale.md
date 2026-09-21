# 0047 — The build that assumes a locale

## Problem

`./gradlew build` cannot compile this repository on a machine with no locale
set. It fails five minutes in, at `:example:compileTestKotlin`, with

```
> Internal compiler error. See log for more details
```

and the cause three hundred lines earlier:

```
e: java.nio.file.InvalidPathException: Malformed input or input contains
unmappable characters: .../McpOverHttpTest$a whole session — the handshake,
the tools, and one of them called$$inlined$withClue$1.class
```

Test names are sentences in backticks, and the Kotlin compiler derives class
file names from them. Two of those sentences contain an em dash. A JVM whose
`sun.jnu.encoding` is `ANSI_X3.4-1968` — which is what an unset `LANG` and
`LC_CTYPE=POSIX` produce, the default in many container images — cannot write
those names, so the compiler dies without saying which file or why.

Nobody on CI has seen this: GitHub's runners set a UTF-8 locale. It is a
contributor's problem, and it presents as the compiler being broken.

## Not doing

- **No change to the naming style.** Sentences in backticks stay. This is about
  two characters that cannot survive a file name, not about prose.
- **No locale set from the build.** Gradle cannot do it; see **Why this shape**.
- **Nothing to CI.** Its runners already have a UTF-8 locale and this has never
  failed there.
- **No change to `SlowConsumerTest`.** Its `Thread.sleep` is a stall to provoke
  a write failure, not a settle — a different question, and spec 0048's.

## Shape

The two names lose their em dashes, and a test in `example` keeps non-ASCII out
of backticked declarations, in the idiom `FunctionalStyleTest` already
establishes for reading sources back:

```kotlin
@Test
fun `no declaration carries a character a file name cannot`() {
    val offenders = testSources()
        .filter { NON_ASCII_DECLARATION.containsMatchIn(it.readText()) }

    withClue("a backticked name becomes a class file name; on an ASCII locale the compiler cannot write it") {
        offenders.shouldBeEmpty()
    }
}
```

Then a guard that fails at configuration rather than five minutes in, for
anything the rule above does not cover:

```kotlin
// build.gradle.kts
val jnu = System.getProperty("sun.jnu.encoding")
check(Charset.forName(jnu).newEncoder().canEncode(SOURCE_TEXT)) {
    "sun.jnu.encoding is $jnu, which cannot encode this build's file names. " +
        "Set a UTF-8 locale — LANG=C.utf8 — and run again. It is read from the " +
        "native locale at JVM startup, so -Dsun.jnu.encoding in org.gradle.jvmargs will not do it."
}
```

## Why this shape

The obvious fix does not work, and the message has to say so or everyone will
try it first. Measured on this repository's JDK:

| | `file.encoding` | `sun.jnu.encoding` |
|---|---|---|
| `-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8`, no locale | UTF-8 | **ANSI_X3.4-1968** |
| `LANG=C.utf8`, no flags | UTF-8 | **UTF-8** |

`sun.jnu.encoding` is read from the native locale when the JVM starts and the
system property is ignored. So `org.gradle.jvmargs` cannot fix this and neither
can the build script — only the environment can, which leaves making the build
not need it, and saying so clearly when something still does.

Removing the two em dashes is what actually fixes it: every checked-in file name
is already ASCII, and those two declarations are the only non-ASCII ones in the
tree, so after them the build compiles on an ASCII locale. The guard is
defence in depth rather than the fix, which is why it is the second entry and
may not earn its place at all.

A source-scanning test rather than a detekt rule because detekt has no general
forbidden-pattern rule, and because this repository already asserts source-level
claims this way — `FunctionalStyleTest`, `NoThirdPartyDependenciesTest`,
`SpiPackageTest`.

## Stack

- [ ] **`spec-0047-ascii-names`** — the two names lose their em dashes, and a
      test keeps non-ASCII out of backticked declarations.
      Done when: `env -u LANG -u LC_ALL ./gradlew build` gets past
      `:example:compileTestKotlin`.
- [x] ~~**`spec-0047-locale-guard`**~~ — not happening as written; see
      **Measured**. What it was for is covered by extending entry one's test to
      source file names.
      Done when: a source file named with a character a file name cannot hold is
      refused by the same gate that refuses a declaration named that way.

## Measured

Entry one landed and `env -u LANG -u LC_ALL ./gradlew build` came back
**`BUILD SUCCESSFUL`** — `:example:compileTestKotlin` included, `468 tests
completed`.

That falsifies entry two's own **Done when**, which asked for a build that
*fails* on an ASCII-only JVM. After entry one the build **works** there, which
was the point. A guard meeting that condition would refuse the exact
configuration entry one just fixed, and a contributor whose container ships no
locale would be told to set one for no reason.

The guard's stated job — "for anything the test above does not cover" — has one
real gap, and it is not a locale question. A source file's own name becomes a
class file name too: `Ünicode.kt` compiles to `ÜnicodeKt.class`, and no
declaration inside it need be unusual for that to fail. That is checked
statically, in the gate that already reads these sources, with a message naming
the file rather than the encoding.

**Entry two does not happen.** The build needs no locale, so nothing should
demand one; what the guard was reaching for is a second assertion in entry one's
test, and that is where it went.

## Acceptance

```bash
env -u LANG -u LC_ALL ./gradlew build
./gradlew build
```

## Open questions

- **Does entry two earn its place?** Answered by building entry one: no. See
  **Measured**.
- **Where does the scan live?** The offending names are in *test* sources, and
  `pelican-core/build.gradle.kts:64` hands `pelican.style.sources` only the main
  ones. Recommend a test in `example` with its own wiring rather than widening
  that property, which `FunctionalStyleTest` asserts the shape of.
- **Em dash to what?** Answered by the compiler: **not a colon**. Kotlin forbids
  `: ; [ ] / < > . \` in a backticked name, because a JVM method name cannot
  hold them — `Name contains illegal characters: :`. An ASCII hyphen is legal
  and keeps the appositive reading, so `a whole session - the handshake, the
  tools, and one of them called`.
- **Should the rule cover all non-ASCII or only what the platform cannot
  encode?** Recommend all non-ASCII in backticked declarations: the rule is then
  readable and machine-independent, and no reviewer has to think about encodings.
