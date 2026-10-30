# Frozen Api consumers

These jars contain only consumer bytecode, never Pelican classes. Normal tests load them with the candidate's
class loader as parent; they need neither a compiler nor a network. `FrozenConsumer.kt` is compiled against both
baselines. Only main also compiles `FrozenCurrentConsumer.kt`, which calls all 25 constructor parameters explicitly.
The common full call uses 21 arguments: released 1.2.0 emits the full descriptor, main emits its 25-field default mask.
Named/default calls, `api`/`api$default`, DSL methods, `ApiBuilder()` and its mangled `build` are frozen too.

## Provenance (SHA-256)

| Input/output | Hash |
|---|---|
| Maven Central `io.github.matthewjones372:pelican-core:1.2.0` | `fef74a6990fdd4e9f1c623293421cebaa6fb0a9a761a145e01a96d5483daca40` |
| Core jar built from main `b9632bd128afbd26a636f6ecc2210a67bc0bfc45` | `418538ae05003f853479eaefd0be4b43097c0e51013b1c9c3dcb35756b105d59` |
| `released-1.2.0.jar` consumer | `fbeeaa722f478cabfd280671094eecbc025fe2fc7b0ddc154e7861eb7135cb54` |
| `main-b9632bd.jar` consumer | `a6af45d181f7157fc02452333779043f82015392b9c38d47d0a5b3ce46bf14f3` |
| `kotlin-compiler-embeddable:2.4.21` | `ef19419c765e7ac8404465fa026ca8fc4dbb8a822de0fc79aa53c8dce29f1d02` |

The released input was downloaded independently from Maven Central and matched the cached original byte for byte.
The main input was built in a separate clean checkout of that exact commit with `./gradlew :pelican-core:jar`.
Both fixture generations used Kotlin 2.4.21 on OpenJDK 27+35-2325, targeting Java 21. Regeneration after formatting
is required because Kotlin records source line numbers. Never regenerate these against a candidate Pelican jar.

## Reproduce

Obtain the baselines above and set `COMPAT_BASELINE` to each original jar in turn, `COMPAT_CLASSES` to an empty output
directory and `COMPAT_JAR` to the corresponding consumer output. `COMPAT_COMPILER_CP` contains Maven artifacts:
Kotlin compiler-embeddable, build-tools-api, stdlib, script-runtime and daemon-embeddable **2.4.21**, kotlin-reflect
**1.6.10**, kotlinx-coroutines-core-jvm **1.8.0**, and org.jetbrains annotations **13.0**. Set `COMPAT_STDLIB` to the
2.4.21 stdlib jar. These are generation tools only, not project/runtime dependencies. Run from this directory:

```sh
java -cp "$COMPAT_COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 21 -module-name frozen_consumer \
  -Xfriend-paths="$COMPAT_BASELINE" -classpath "$COMPAT_BASELINE:$COMPAT_STDLIB" \
  -d "$COMPAT_CLASSES" FrozenConsumer.kt
jar --create --file "$COMPAT_JAR" --date=2026-10-30T00:00:00Z -C "$COMPAT_CLASSES" .
```

For main, append `FrozenCurrentConsumer.kt` to the compiler command before packaging. The friend flag deliberately
permits tests of original internal constructors/build entry points; no production visibility changes. The jar's
fixed timestamp makes repeat generation byte-identical. Inspect with `javap -p -c -classpath "$COMPAT_JAR"`.

Before the bridge, seven runtime checks produced two expected `NoSuchMethodError` failures (released full and
released default-mask); five current/factory checks passed. The bridge makes all seven pass, preserving every
setting and pinning every original default. Idempotency comparisons inspect store/ttl/lease, since the DSL copies it.
