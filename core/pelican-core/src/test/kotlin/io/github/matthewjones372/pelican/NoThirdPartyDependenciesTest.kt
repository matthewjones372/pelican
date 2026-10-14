package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * `pelican-core` is meant to be describable-in-a-vacuum: endpoint values, a
 * minimal JSON tree, and one parser under it. This is the same idea as
 * `DecouplingTest`'s Pekko-absence check, one level stricter — rather than
 * naming the libraries that must be absent, it names the only ones that may be
 * present and asserts nothing else is there at all.
 *
 * `jackson-core` is on that list by a decision recorded in spec 0045, and it is
 * the streaming parser alone. The second test below is what keeps that from
 * drifting into databind, which is the dependency this module exists without.
 */
class NoThirdPartyDependenciesTest {

    /**
     * Artifacts the Kotlin plugin puts on every module's classpath, and the one
     * this module declares. A name added here is an architectural decision and
     * wants a spec behind it.
     */
    private val allowed = listOf("kotlin-stdlib", "annotations-", "jackson-core-")

    @Test
    fun `the main runtime classpath is the kotlin standard library and the one parser`() {
        val raw = System.getProperty("pelican.core.runtimeClasspath")
        withClue("the build must pass -Dpelican.core.runtimeClasspath; see build.gradle.kts") { raw.shouldNotBeNull() }

        val unexpected = raw!!.split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("pelican-core takes no runtime dependency beyond $allowed, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }

    /**
     * The parser is in; the object mapper is not. Reading a document into
     * [JsonValue] is a different job from binding one to a class, and it is
     * binding that would decide the codec for every service.
     */
    @Test
    fun `no json databind is reachable from core`() {
        listOf(
            "kotlinx.serialization.json.Json",
            "com.fasterxml.jackson.databind.ObjectMapper",
            "org.apache.pekko.http.javadsl.server.Directives",
        ).forEach { name ->
            withClue("$name is on core's classpath; a dependency crept in") {
                shouldThrow<ClassNotFoundException> { Class.forName(name) }
            }
        }
    }

    @Test
    fun `a schema can still be represented without one`() {
        val doc = jsonObj {
            "type" to "object"
            put("properties", jsonObj { "id" to jsonObj { "type" to "integer" } })
            put("required", jsonStrings(listOf("id")))
        }
        doc.render() shouldBe """{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}"""
    }
}
