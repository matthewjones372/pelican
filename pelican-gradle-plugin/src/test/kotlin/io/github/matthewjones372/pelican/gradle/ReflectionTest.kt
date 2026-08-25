package io.github.matthewjones372.pelican.gradle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReflectionTest {

    private val loader = javaClass.classLoader

    @Test
    fun `calls a top-level function`() {
        val spec = Pelican.spec(loader, "io.github.matthewjones372.pelican.gradle.SpecsKt", "ordersSpec")
        Pelican.document(loader, spec, DocumentFormat.JSON) shouldBe """{"title":"Orders"}"""
    }

    @Test
    fun `calls a member of an object`() {
        val spec = Pelican.spec(loader, "io.github.matthewjones372.pelican.gradle.Specs", "spec")
        Pelican.document(loader, spec, DocumentFormat.YAML) shouldBe "title: Bookmarks\n"
    }

    @Test
    fun `calls a member of a class it has to instantiate`() {
        val spec = Pelican.spec(loader, "io.github.matthewjones372.pelican.gradle.Holder", "spec")
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
            Pelican.spec(loader, "io.github.matthewjones372.pelican.gradle.SpecsKt", "orderSpec")
        }
        failure.message.orEmpty() shouldContain "no no-argument `orderSpec()`"
        failure.message.orEmpty() shouldContain "ordersSpec"
    }

    @Test
    fun `says so when the function returns something else`() {
        shouldThrow<PelicanFailure> {
            Pelican.spec(loader, "io.github.matthewjones372.pelican.gradle.SpecsKt", "notASpec")
        }.message.orEmpty() shouldContain "returned a java.lang.String"
    }

    @Test
    fun `says so when the class cannot be instantiated`() {
        shouldThrow<PelicanFailure> {
            Pelican.spec(loader, "io.github.matthewjones372.pelican.gradle.Uninstantiable", "spec")
        }.message.orEmpty() shouldContain "cannot be instantiated"
    }

    /** A spec that throws is the consumer's own failure, and reads as one. */
    @Test
    fun `lets the spec's own failure through`() {
        shouldThrow<IllegalStateException> {
            Pelican.spec(loader, "io.github.matthewjones372.pelican.gradle.SpecsKt", "throwingSpec")
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
        val spec = Pelican.spec(loader, "io.github.matthewjones372.pelican.gradle.SpecsKt", "ordersSpec")
        val written = Pelican.writeClient(loader, spec, dir, "example.generated", null, null, false, null)

        written shouldBe File(dir, "example/generated/OrdersClient.kt")
        // title | package | client | base URL | hidden | codec
        written.readText().trim() shouldBe "Orders|example.generated|OrdersClient|https://orders.test|false|JACKSON"
    }

    @Test
    fun `passes what the entry set instead`(@TempDir dir: File) {
        val spec = Pelican.spec(loader, "io.github.matthewjones372.pelican.gradle.SpecsKt", "ordersSpec")
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
        val spec = Pelican.spec(loader, "io.github.matthewjones372.pelican.gradle.SpecsKt", "ordersSpec")
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
            mapOf("Payment" to "kind", "Order/properties/payment" to "type"),
            setOf("https://schemas.test"),
            File(dir, "orders.refs.lock"),
        )

        written shouldBe listOf(File(dir, "com/example/orders/OrdersEndpoints.kt"))
        (written.single() as File).readText().trim() shouldBe
            "openapi.yaml|com.example.orders|orders|a+b|pekko|kotlinx|" +
            "Order/properties/payment=type+Payment=kind|https://schemas.test|orders.refs.lock"
    }

    @Test
    fun `names the module to add when the importer is not on the classpath`(@TempDir dir: File) {
        val empty = java.net.URLClassLoader(emptyArray(), ClassLoader.getPlatformClassLoader())
        shouldThrow<PelicanFailure> {
            Pelican.writeEndpoints(
                empty,
                dir,
                dir,
                "com.example",
                "orders",
                emptySet(),
                null,
                null,
                emptyMap(),
                emptySet(),
                null,
            )
        }.message.orEmpty() shouldContain "pelican-import"
    }

    @Test
    fun `writes the lockfile through the function the library publishes`(@TempDir dir: File) {
        val document = File(dir, "openapi.yaml").apply { writeText("openapi: 3.1.0") }
        val lockfile = File(dir, "orders.refs.lock")

        Pelican.updateLock(loader, document, lockfile, "orders", setOf("https://schemas.test"), true) shouldBe
            listOf("Wrote $lockfile")
        lockfile.readText().trim() shouldBe "openapi.yaml|orders|https://schemas.test|true"
    }

    @Test
    fun `says which importer has no lockfile to write`(@TempDir dir: File) {
        val failure = shouldThrow<PelicanFailure> {
            Pelican.updateLock(previousImporter, dir, File(dir, "x.lock"), "orders", setOf("https://a.test"), false)
        }

        failure.message.orEmpty() shouldContain "pelican-import"
        failure.message.orEmpty() shouldContain "older than remote references"
    }

    /** No servers and no `baseUrl` is allowed: the caller passes one instead. */
    @Test
    fun `leaves the base URL unset when there is nothing to default to`(@TempDir dir: File) {
        val spec = Pelican.spec(loader, "io.github.matthewjones372.pelican.gradle.SpecsKt", "serverlessSpec")
        val written = Pelican.writeClient(loader, spec, dir, "example", null, null, false, null)

        written.readText().trim() shouldBe "Orders|example|OrdersClient|null|false|JACKSON"
    }

    // ------------------------------------------------ the older libraries

    private val olderCodegen = Class.forName("io.github.matthewjones372.pelican.older.codegen.KotlinClientKt")
    private val previousImporter = Class.forName("io.github.matthewjones372.pelican.previous.importer.ImportKt")
    private val olderImporter = Class.forName("io.github.matthewjones372.pelican.older.importer.ImportKt")
    private val oldestImporter = Class.forName("io.github.matthewjones372.pelican.oldest.importer.ImportKt")
    private val apiSpec = Class.forName("io.github.matthewjones372.pelican.ApiSpec")

    @Test
    fun `writes a client through the arity an older library published`(@TempDir dir: File) {
        val spec = Pelican.spec(loader, "io.github.matthewjones372.pelican.gradle.SpecsKt", "ordersSpec")
        val written =
            Pelican.writeClient(olderCodegen, apiSpec, spec, dir, "example", null, null, false, null)

        written shouldBe File(dir, "example/OrdersClient.kt")
        written.readText().trim() shouldBe "Orders|example|OrdersClient|https://orders.test|false"
    }

    @Test
    fun `says which library is too old to carry the codec the entry set`(@TempDir dir: File) {
        val spec = Pelican.spec(loader, "io.github.matthewjones372.pelican.gradle.SpecsKt", "ordersSpec")
        val failure = shouldThrow<PelicanFailure> {
            Pelican.writeClient(olderCodegen, apiSpec, spec, dir, "example", null, null, false, "kotlinx")
        }

        failure.message.orEmpty() shouldContain "pelican-codegen"
        failure.message.orEmpty() shouldContain "takes no codec"
    }

    /** One step down: no allowlist, and the hints still carried rather than dropped. */
    @Test
    fun `imports through the arity published before remote references`(@TempDir dir: File) {
        val document = File(dir, "openapi.yaml").apply { writeText("openapi: 3.1.0") }
        val written = Pelican.writeEndpoints(
            previousImporter,
            document,
            dir,
            "com.example",
            "orders",
            setOf("a"),
            "ktor",
            "kotlinx",
            mapOf("Payment" to "kind"),
            emptySet(),
            null,
        )

        (written.single() as File).readText().trim() shouldBe
            "openapi.yaml|com.example|orders|a|ktor|kotlinx|Payment=kind"
    }

    @Test
    fun `says which importer is too old to carry the remote allowlist`(@TempDir dir: File) {
        val failure = shouldThrow<PelicanFailure> {
            Pelican.writeEndpoints(
                previousImporter,
                dir,
                dir,
                "com.example",
                "orders",
                emptySet(),
                null,
                null,
                emptyMap(),
                setOf("https://schemas.test"),
                File(dir, "orders.refs.lock"),
            )
        }

        failure.message.orEmpty() shouldContain "`allowRemote(...)` is set"
        failure.message.orEmpty() shouldContain "pelican-import"
        failure.message.orEmpty() shouldContain "takes no allowlist"
    }

    /** Two steps down: no hints, and the codec still carried rather than dropped. */
    @Test
    fun `imports through the arity published before the discriminator hints`(@TempDir dir: File) {
        val document = File(dir, "openapi.yaml").apply { writeText("openapi: 3.1.0") }
        val written = Pelican.writeEndpoints(
            olderImporter,
            document,
            dir,
            "com.example",
            "orders",
            setOf("a"),
            "ktor",
            "kotlinx",
            emptyMap(),
            emptySet(),
            null,
        )

        (written.single() as File).readText().trim() shouldBe "openapi.yaml|com.example|orders|a|ktor|kotlinx"
    }

    @Test
    fun `says which importer is too old to carry the discriminator hints`(@TempDir dir: File) {
        val failure = shouldThrow<PelicanFailure> {
            Pelican.writeEndpoints(
                olderImporter,
                dir,
                dir,
                "com.example",
                "orders",
                emptySet(),
                null,
                null,
                mapOf("Payment" to "kind"),
                emptySet(),
                null,
            )
        }

        failure.message.orEmpty() shouldContain "`discriminator(...)` is set"
        failure.message.orEmpty() shouldContain "pelican-import"
        failure.message.orEmpty() shouldContain "takes no hints"
    }

    /** Two steps down: the arity from before the codec, which is the bottom. */
    @Test
    fun `imports through the arity an older library published`(@TempDir dir: File) {
        val document = File(dir, "openapi.yaml").apply { writeText("openapi: 3.1.0") }
        val written = Pelican.writeEndpoints(
            oldestImporter,
            document,
            dir,
            "com.example",
            "orders",
            setOf("a"),
            "ktor",
            null,
            emptyMap(),
            emptySet(),
            null,
        )

        (written.single() as File).readText().trim() shouldBe "openapi.yaml|com.example|orders|a|ktor"
    }

    @Test
    fun `says which importer is too old to carry the codec the entry set`(@TempDir dir: File) {
        val failure = shouldThrow<PelicanFailure> {
            Pelican.writeEndpoints(
                oldestImporter,
                dir,
                dir,
                "com.example",
                "orders",
                emptySet(),
                null,
                "kotlinx",
                emptyMap(),
                emptySet(),
                null,
            )
        }

        failure.message.orEmpty() shouldContain "pelican-import"
        failure.message.orEmpty() shouldContain "takes no codec"
    }
}
