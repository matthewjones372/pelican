package dev.pelican

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
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
 */
class FunctionalStyleTest {

    private val builders = mapOf(
        "pelican-core/src/main/kotlin/dev/pelican/Endpoint.kt" to
            "EndpointBuilder collects declarations as they are made and freezes them into an Endpoint",
        "pelican-core/src/main/kotlin/dev/pelican/JsonValue.kt" to
            "the jsonObj/jsonArr DSL, which is an accumulate-then-render builder by definition",
        "pelican-core/src/main/kotlin/dev/pelican/Cors.kt" to
            "the allowed-headers set is built once per Api and read from every request after",
        "pelican-core/src/main/kotlin/dev/pelican/Forms.kt" to
            "form decoding gathers repeated keys before deciding what the value is",
        "pelican-core/src/main/kotlin/dev/pelican/Security.kt" to
            "scheme registration accumulates, then the document reads it",
        "pelican-core/src/main/kotlin/dev/pelican/Params.kt" to
            "the attribute bag a filter writes into: mutable for the length of one request, by design",
        "pelican-core/src/main/kotlin/dev/pelican/BodyCodec.kt" to
            "codecs are resolved once per type and cached against it",
        "pelican-openapi/src/main/kotlin/dev/pelican/openapi/OpenApi.kt" to
            "component schemas are collected as the document is walked, then emitted once",
        "pelican-codegen/src/main/kotlin/dev/pelican/codegen/KotlinClient.kt" to
            "the emitter builds one client method's parameter list, keeping names unique as it goes",
        "pelican-codegen/src/main/kotlin/dev/pelican/codegen/KotlinTypes.kt" to
            "generated type names are registered as they are minted, so a second use finds the first",
        "pelican-jackson/src/main/kotlin/dev/pelican/jackson/KotlinAwareModelResolver.kt" to
            "swagger's Schema is a mutable Java bean; patching it is the integration",
        "pelican-kotlinx/src/main/kotlin/dev/pelican/kotlinx/DescriptorSchemas.kt" to
            "the same, for kotlinx.serialization descriptors",

        // The three interpreters fill one bag of decoded values per request and
        // hand it to Params, which is the request's own scope and nothing
        // wider. Everything decidable before that — the inputs themselves — is
        // built as values by `plainInputs` and merged in.
        "pelican-pekko/src/main/kotlin/dev/pelican/pekko/Interpreter.kt" to
            "the per-request value bag handed to Params",
        "pelican-http4k/src/main/kotlin/dev/pelican/http4k/Interpreter.kt" to
            "the per-request value bag handed to Params",
        "pelican-ktor/src/main/kotlin/dev/pelican/ktor/Interpreter.kt" to
            "the per-request value bag handed to Params",
        "pelican-pekko/src/main/kotlin/dev/pelican/pekko/Responses.kt" to
            "an IdentityHashMap of declared failure to codec, resolved once when the route is built",
    )

    /** The library modules. The example is a service, and holds its store in a map on purpose. */
    private val modules = listOf(
        "pelican-core", "pelican-openapi", "pelican-codegen", "pelican-jackson", "pelican-kotlinx",
        "pelican-pekko", "pelican-http4k", "pelican-ktor",
        "pelican-test", "pelican-test-pekko", "pelican-test-http4k",
    )

    private val accumulators = Regex(
        """\b(mutableListOf|mutableMapOf|mutableSetOf""" +
            """|LinkedHashMap|LinkedHashSet|IdentityHashMap|ArrayList|HashMap|HashSet)\s*[(<]""",
    )

    private fun repoRoot(): File {
        val here = File("").absoluteFile
        return if (here.name == "pelican-core") here.parentFile else here
    }

    /**
     * `src/main/kotlin` only. `pelican-codegen/src/main/resources` holds `.kt`
     * files that are templates the generator reads at runtime — they are text,
     * not this build's source, and Spotless skips them for the same reason.
     */
    private fun mainSources(): List<File> = modules
        .map { File(repoRoot(), "$it/src/main/kotlin") }
        .filter { it.isDirectory }
        .flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList() }

    @Test
    fun `mutable collections are built only where a builder was meant to be`() {
        val root = repoRoot()
        val found = mainSources()
            .filter { accumulators.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).path }
            .toSortedSet()

        withClue("the source tree was not found; this test reads files, and looked in $root") {
            (mainSources().size > 20) shouldBe true
        }

        val unexpected = found - builders.keys
        withClue("a mutable accumulator appeared outside a builder: $unexpected") {
            unexpected.isEmpty() shouldBe true
        }

        val stale = builders.keys - found
        withClue("these no longer accumulate; drop them from the list: $stale") {
            stale.isEmpty() shouldBe true
        }
    }
}
