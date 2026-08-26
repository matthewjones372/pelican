package io.github.matthewjones372.pelican

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Where mutable state is allowed to live, stated as a test.
 *
 * This started as a detekt rule — `ForbiddenMethodCall` on `mutableListOf` and
 * friends — and that rule reported 28 sites under Kotlin 2.2 and silently zero
 * after the compiler moved to 2.4: detekt 1.23.8 resolves `java.util.UUID.
 * randomUUID` against the new metadata but not `kotlin.collections.
 * mutableListOf`. A rule that stops firing without saying so is worse than no
 * rule, so the claim is made here instead, where it cannot go quiet.
 *
 * It is a ratchet rather than a ban. Every file below accumulates into a
 * mutable collection and then hands back something immutable, which is what a
 * builder is; banning that would not make the code more functional, only more
 * ceremonious. What the list stops is the *next* file quietly starting to do
 * it — a new mutable accumulator has to be argued for here, in writing, before
 * the build goes green again.
 *
 * Which sources it judges is not decided here: the build hands them over and
 * declares the same directories as inputs of the task that runs this test. A
 * test that resolved them itself would read files Gradle knows nothing about,
 * and a violation would ride green builds until `--rerun-tasks`. See
 * `pelican-core/build.gradle.kts`.
 */
class FunctionalStyleTest {

    private val builders = mapOf(
        "pelican-core/src/main/kotlin/io/github/matthewjones372/pelican/ApiBuilder.kt" to
            "the filter chain accumulates a line at a time, and the Api it returns is handed a copy",
        "pelican-core/src/main/kotlin/io/github/matthewjones372/pelican/Endpoint.kt" to
            "EndpointBuilder collects declarations as they are made and freezes them into an Endpoint",
        "pelican-core/src/main/kotlin/io/github/matthewjones372/pelican/JsonValue.kt" to
            "the jsonObj/jsonArr DSL, which is an accumulate-then-render builder by definition",
        "pelican-core/src/main/kotlin/io/github/matthewjones372/pelican/Cors.kt" to
            "the allowed-headers set is built once per Api and read from every request after",
        "pelican-core/src/main/kotlin/io/github/matthewjones372/pelican/Forms.kt" to
            "form decoding gathers repeated keys before deciding what the value is",
        "pelican-core/src/main/kotlin/io/github/matthewjones372/pelican/Security.kt" to
            "scheme registration accumulates, then the document reads it",
        "pelican-core/src/main/kotlin/io/github/matthewjones372/pelican/Params.kt" to
            "the attribute bag a filter writes into: mutable for the length of one request, by design",
        "pelican-core/src/main/kotlin/io/github/matthewjones372/pelican/spi/RouteIndex.kt" to
            "the walk collects captured segments as it descends and unwinds them when a branch " +
            "fails, which is what lets a literal be tried before a capture without matching twice",
        "pelican-core/src/main/kotlin/io/github/matthewjones372/pelican/BodyCodec.kt" to
            "codecs are resolved once per type and cached against it, and SchemaNames remembers which " +
            "type claimed each component name while one is being described",
        "pelican-core/src/main/kotlin/io/github/matthewjones372/pelican/InMemory.kt" to
            "the per-request value bag handed to Params, as in each interpreter",
        "pelican-core/src/main/kotlin/io/github/matthewjones372/pelican/spi/Requests.kt" to
            "the response-to-codec table, keyed by identity because two responses can carry one payload " +
            "type and a negotiated one carries the same type under several encodings; built once per " +
            "endpoint and handed to the interpreter as a Map",
        "pelican-openapi/src/main/kotlin/io/github/matthewjones372/pelican/openapi/OpenApi.kt" to
            "component schemas are collected as the document is walked, then emitted once",
        "pelican-schema/src/main/kotlin/io/github/matthewjones372/pelican/schema/StandaloneSchemas.kt" to
            "SchemaComponents is written into as a type is walked, and the document freezes it into `\$defs`",
        "pelican-codegen/src/main/kotlin/io/github/matthewjones372/pelican/codegen/KotlinClient.kt" to
            "the emitter builds one client method's parameter list, keeping names unique as it goes",
        "pelican-codegen/src/main/kotlin/io/github/matthewjones372/pelican/codegen/KotlinTypes.kt" to
            "generated type names are registered as they are minted, so a second use finds the first",
        "pelican-codegen/src/main/kotlin/io/github/matthewjones372/pelican/codegen/Unions.kt" to
            "merging an `allOf` gathers the properties its branches declare, then freezes them into one schema",
        "pelican-jackson/src/main/kotlin/io/github/matthewjones372/pelican/jackson/KotlinAwareModelResolver.kt" to
            "swagger's Schema is a mutable Java bean; patching it is the integration",

        "pelican-pekko/src/main/kotlin/io/github/matthewjones372/pelican/pekko/Interpreter.kt" to
            "the per-request value bag handed to Params",
        "pelican-pekko/src/main/kotlin/io/github/matthewjones372/pelican/pekko/Responses.kt" to
            "the empty parameter map Pekko's customWithFixedCharset takes, and the cache of the content " +
            "types built from it — one entry per declared media type, none of them from a request",
    )

    private val accumulators = Regex(
        """\b(mutableListOf|mutableMapOf|mutableSetOf""" +
            """|LinkedHashMap|LinkedHashSet|IdentityHashMap|ArrayList|HashMap|HashSet)\s*[(<]""",
    )

    /** Absent means the build's wiring is gone, which is the failure this test cannot survive. */
    private fun handedOver(name: String): String {
        val value = System.getProperty(name)
        withClue("the build must pass -D$name; see pelican-core/build.gradle.kts") { value.shouldNotBeNull() }
        return value!!
    }

    private fun repoRoot(): File = File(handedOver("pelican.style.repoRoot"))

    private fun sourceRoots(): List<File> = handedOver("pelican.style.sources")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map(::File)

    private fun mainSources(): List<File> = sourceRoots()
        .flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList() }

    /**
     * The wiring, asserted rather than assumed.
     *
     * A test can see the properties the build passed it; it cannot see the
     * task's declared inputs, and those two lines are one decision — they are
     * written together in `build.gradle.kts` and say so. What this catches is
     * the regression that actually happened: the gate reading a tree nobody
     * told Gradle about. Delete the wiring and this fails immediately, rather
     * than the gate quietly reverting to guessing where the sources are.
     */
    @Test
    fun `the build hands this test the sources it judges`() {
        val roots = sourceRoots()
        withClue("the build named no source roots at all") { roots.shouldNotBeEmpty() }

        val missing = roots.filterNot { it.isDirectory }
        withClue("the build named source roots that are not there: $missing") { missing.shouldBeEmpty() }

        val outside = roots.filterNot { it.absoluteFile.startsWith(repoRoot().absoluteFile) }
        withClue("a source root sits outside ${repoRoot()}, so the paths below cannot be keys: $outside") {
            outside.shouldBeEmpty()
        }

        withClue("only ${mainSources().size} files were handed over; the gate is judging almost nothing") {
            mainSources().size shouldBeGreaterThan 20
        }
    }

    @Test
    fun `mutable collections are built only where a builder was meant to be`() {
        val root = repoRoot()
        val found = mainSources()
            .filter { accumulators.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).path }
            .toSortedSet()

        val unexpected = found - builders.keys
        withClue("a mutable accumulator appeared outside a builder: $unexpected") {
            unexpected.shouldBeEmpty()
        }

        val stale = builders.keys - found
        withClue("these no longer accumulate; drop them from the list: $stale") {
            stale.shouldBeEmpty()
        }
    }
}
