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
        val written = Pelican.writeClient(loader, spec, dir, "example.generated", null, null, false, null)

        written shouldBe File(dir, "example/generated/OrdersClient.kt")
        // title | package | client | base URL | hidden | codec
        written.readText().trim() shouldBe "Orders|example.generated|OrdersClient|https://orders.test|false|JACKSON"
    }

    @Test
    fun `passes what the entry set instead`(@TempDir dir: File) {
        val spec = Pelican.spec(loader, "dev.pelican.gradle.SpecsKt", "ordersSpec")
        val written =
            Pelican.writeClient(loader, spec, dir, "example", "Internal", "https://elsewhere.test", true, "kotlinx")

        written shouldBe File(dir, "example/Internal.kt")
        written.readText().trim() shouldBe "Orders|example|Internal|https://elsewhere.test|true|KOTLINX"
    }

    /**
     * The build file says `kotlinx` and the library declares `KOTLINX`. The
     * plugin holds the enum's name and not its constants, so the matching is
     * the only place the two spellings meet.
     */
    @Test
    fun `names the codecs the library offers when the entry names one it does not`(@TempDir dir: File) {
        val spec = Pelican.spec(loader, "dev.pelican.gradle.SpecsKt", "ordersSpec")
        val failure = shouldThrow<PelicanFailure> {
            Pelican.writeClient(loader, spec, dir, "example", null, null, false, "gson")
        }

        failure.message.orEmpty() shouldContain "No codec called 'gson'"
        failure.message.orEmpty() shouldContain "JACKSON, KOTLINX"
    }

    @Test
    fun `imports a document into a source root`(@TempDir dir: File) {
        val document = File(dir, "openapi.yaml").apply { writeText("openapi: 3.1.0") }
        val written = Pelican.writeEndpoints(
            loader,
            document,
            dir,
            "com.example.orders",
            "orders",
            setOf("b", "a"),
            "pekko",
            "kotlinx",
        )

        written shouldBe listOf(File(dir, "com/example/orders/OrdersEndpoints.kt"))
        (written.single() as File).readText().trim() shouldBe
            "openapi.yaml|com.example.orders|orders|a+b|pekko|kotlinx"
    }

    @Test
    fun `names the module to add when the importer is not on the classpath`(@TempDir dir: File) {
        val empty = java.net.URLClassLoader(emptyArray(), ClassLoader.getPlatformClassLoader())
        shouldThrow<PelicanFailure> {
            Pelican.writeEndpoints(empty, dir, dir, "com.example", "orders", emptySet(), null, null)
        }.message.orEmpty() shouldContain "pelican-import"
    }

    /** No servers and no `baseUrl` is allowed: the caller passes one instead. */
    @Test
    fun `leaves the base URL unset when there is nothing to default to`(@TempDir dir: File) {
        val spec = Pelican.spec(loader, "dev.pelican.gradle.SpecsKt", "serverlessSpec")
        val written = Pelican.writeClient(loader, spec, dir, "example", null, null, false, null)

        written.readText().trim() shouldBe "Orders|example|OrdersClient|null|false|JACKSON"
    }

    // ------------------------------------------------- an older library

    /**
     * The other half of every lookup above: a `pelican-codegen` or a
     * `pelican-import` published before the codec setting existed.
     *
     * Both are whole library versions standing in their own packages, and the
     * tests reach them through the seam that takes a resolved class. Naming the
     * class is the only way to have two versions on one test classpath — and
     * without two versions the fallback would be a branch nothing ever takes,
     * which is a fallback that has already stopped working and not said so.
     */
    private val olderCodegen = Class.forName("dev.pelican.older.codegen.KotlinClientKt")
    private val olderImporter = Class.forName("dev.pelican.older.importer.ImportKt")
    private val apiSpec = Class.forName("dev.pelican.ApiSpec")

    @Test
    fun `writes a client through the arity an older library published`(@TempDir dir: File) {
        val spec = Pelican.spec(loader, "dev.pelican.gradle.SpecsKt", "ordersSpec")
        val written =
            Pelican.writeClient(olderCodegen, apiSpec, spec, dir, "example", null, null, false, null)

        written shouldBe File(dir, "example/OrdersClient.kt")
        written.readText().trim() shouldBe "Orders|example|OrdersClient|https://orders.test|false"
    }

    @Test
    fun `says which library is too old to carry the codec the entry set`(@TempDir dir: File) {
        val spec = Pelican.spec(loader, "dev.pelican.gradle.SpecsKt", "ordersSpec")
        val failure = shouldThrow<PelicanFailure> {
            Pelican.writeClient(olderCodegen, apiSpec, spec, dir, "example", null, null, false, "kotlinx")
        }

        failure.message.orEmpty() shouldContain "pelican-codegen"
        failure.message.orEmpty() shouldContain "takes no codec"
    }

    @Test
    fun `imports through the arity an older library published`(@TempDir dir: File) {
        val document = File(dir, "openapi.yaml").apply { writeText("openapi: 3.1.0") }
        val written =
            Pelican.writeEndpoints(olderImporter, document, dir, "com.example", "orders", setOf("a"), "ktor", null)

        (written.single() as File).readText().trim() shouldBe "openapi.yaml|com.example|orders|a|ktor"
    }

    @Test
    fun `says which importer is too old to carry the codec the entry set`(@TempDir dir: File) {
        val failure = shouldThrow<PelicanFailure> {
            Pelican.writeEndpoints(olderImporter, dir, dir, "com.example", "orders", emptySet(), null, "kotlinx")
        }

        failure.message.orEmpty() shouldContain "pelican-import"
        failure.message.orEmpty() shouldContain "takes no codec"
    }
}
