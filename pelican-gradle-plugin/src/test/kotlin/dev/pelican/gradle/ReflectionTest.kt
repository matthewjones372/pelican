package dev.pelican.gradle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The names, pinned.
 *
 * Everything the plugin knows about Pelican is a string, because it loads the
 * library off the consumer's classpath rather than compiling against it. That
 * buys a plugin whose version and the library's move independently, and it
 * costs the compiler's opinion on every one of these calls — so the calls are
 * made here instead, against classes carrying the same signatures.
 *
 * What this cannot check is that the real signatures are still these. The
 * example module does that, by applying the plugin and generating the client
 * this repository commits.
 */
class ReflectionTest {

    private val loader = javaClass.classLoader

    @Test
    fun `calls a top-level function`() {
        val spec = Pelican.spec(loader, "dev.pelican.gradle.SpecsKt", "ordersSpec")
        Pelican.document(loader, spec, DocumentFormat.JSON) shouldBe """{"title":"Orders"}"""
    }

    @Test
    fun `calls a member of an object`() {
        val spec = Pelican.spec(loader, "dev.pelican.gradle.Specs", "spec")
        Pelican.document(loader, spec, DocumentFormat.YAML) shouldBe "title: Bookmarks\n"
    }

    @Test
    fun `calls a member of a class it has to instantiate`() {
        val spec = Pelican.spec(loader, "dev.pelican.gradle.Holder", "spec")
        Pelican.document(loader, spec, DocumentFormat.JSON) shouldBe """{"title":"Reports"}"""
    }

    @Test
    fun `names the class it could not find, and how the name is formed`() {
        val failure = shouldThrow<PelicanFailure> {
            Pelican.spec(loader, "example.Orders", "ordersSpec")
        }
        failure.message.orEmpty() shouldContain "No class `example.Orders`"
        failure.message.orEmpty() shouldContain "OrdersKt"
    }

    @Test
    fun `lists what it found when the function is not there`() {
        val failure = shouldThrow<PelicanFailure> {
            Pelican.spec(loader, "dev.pelican.gradle.SpecsKt", "orderSpec")
        }
        failure.message.orEmpty() shouldContain "no no-argument `orderSpec()`"
        failure.message.orEmpty() shouldContain "ordersSpec"
    }

    @Test
    fun `says so when the function returns something else`() {
        shouldThrow<PelicanFailure> {
            Pelican.spec(loader, "dev.pelican.gradle.SpecsKt", "notASpec")
        }.message.orEmpty() shouldContain "returned a java.lang.String"
    }

    @Test
    fun `says so when the class cannot be instantiated`() {
        shouldThrow<PelicanFailure> {
            Pelican.spec(loader, "dev.pelican.gradle.Uninstantiable", "spec")
        }.message.orEmpty() shouldContain "cannot be instantiated"
    }

    /** A spec that throws is the consumer's own failure, and reads as one. */
    @Test
    fun `lets the spec's own failure through`() {
        shouldThrow<IllegalStateException> {
            Pelican.spec(loader, "dev.pelican.gradle.SpecsKt", "throwingSpec")
        }.message shouldBe "the spec itself failed"
    }

    @Test
    fun `names the module to add when the library is not on the classpath`() {
        val empty = java.net.URLClassLoader(emptyArray(), ClassLoader.getPlatformClassLoader())
        shouldThrow<PelicanFailure> {
            Pelican.document(empty, "not a spec", DocumentFormat.JSON)
        }.message.orEmpty() shouldContain "pelican-core"
    }

    @Test
    fun `falls back to the client name and base URL the library would have used`(@TempDir dir: File) {
        val spec = Pelican.spec(loader, "dev.pelican.gradle.SpecsKt", "ordersSpec")
        val written = Pelican.writeClient(loader, spec, dir, "example.generated", null, null, false)

        written shouldBe File(dir, "example/generated/OrdersClient.kt")
        // title | package | client | base URL | hidden
        written.readText().trim() shouldBe "Orders|example.generated|OrdersClient|https://orders.test|false"
    }

    @Test
    fun `passes what the entry set instead`(@TempDir dir: File) {
        val spec = Pelican.spec(loader, "dev.pelican.gradle.SpecsKt", "ordersSpec")
        val written = Pelican.writeClient(loader, spec, dir, "example", "Internal", "https://elsewhere.test", true)

        written shouldBe File(dir, "example/Internal.kt")
        written.readText().trim() shouldBe "Orders|example|Internal|https://elsewhere.test|true"
    }

    /** No servers and no `baseUrl` is allowed: the caller passes one instead. */
    @Test
    fun `leaves the base URL unset when there is nothing to default to`(@TempDir dir: File) {
        val spec = Pelican.spec(loader, "dev.pelican.gradle.SpecsKt", "serverlessSpec")
        val written = Pelican.writeClient(loader, spec, dir, "example", null, null, false)

        written.readText().trim() shouldBe "Orders|example|OrdersClient|null|false"
    }
}
