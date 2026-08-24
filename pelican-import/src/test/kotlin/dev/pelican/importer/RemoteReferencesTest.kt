package dev.pelican.importer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * References to another host: the way through the refusal, and every place it
 * still refuses.
 *
 * The refusal itself is in `ReferencesTest`, where it belongs — a build that
 * fetches a URL to know what to generate cannot be reproduced, and that is
 * still what happens to a document nobody has said anything about. What is
 * asserted here is the bargain that makes an exception safe: the host is named
 * in the build file, every URL reached is recorded with the hash of what came
 * back, and nothing is read that has not been checked against that record.
 *
 * So the tests that matter most are the ones where something has gone wrong.
 * A fetch that works is one test; a document that changed under a recorded
 * hash, a redirect, a host nobody allowed and a cache somebody edited are the
 * rest of the file, because each of them is a way a hash check gets quietly
 * neutered.
 */
class RemoteReferencesTest {

    // ------------------------------------------------------------ the way in

    @Test
    fun `an allowed host is fetched, and the schema keeps the name it had there`(@TempDir dir: File) {
        Stub().serving("/common.yaml", widget).use { stub ->
            val document = documentOf("openapi.yaml" to referring(stub.url("/common.yaml#/components/schemas/Widget")))
            val options = allowing(stub, dir)
            Import.updateLock(document, options, acceptChanges = false)

            val generated = imported(document, options)
            generated shouldContain "data class Widget("
            generated shouldContain "val id: String"
        }
    }

    /**
     * A fetched document referring to its neighbour is the ordinary shape of a
     * published spec, so the closure is followed — and every step of it is in
     * the lockfile, which is what makes the *whole* of what a build reads
     * something somebody reviewed rather than only the first hop.
     */
    @Test
    fun `a fetched document's own references are followed, and every one is recorded`(@TempDir dir: File) {
        Stub()
            .serving("/common.yaml", widgetWithColour)
            .serving("/colour.yaml", colour)
            .use { stub ->
                val document =
                    documentOf("openapi.yaml" to referring(stub.url("/common.yaml#/components/schemas/Widget")))
                val options = allowing(stub, dir)
                Import.updateLock(document, options, acceptChanges = false)

                val recorded = lockfile(dir).readLines().filterNot { it.startsWith("#") }.filter { it.isNotBlank() }
                recorded.map { it.substringBefore(' ') } shouldBe
                    listOf(stub.url("/colour.yaml"), stub.url("/common.yaml"))

                val generated = imported(document, options)
                generated shouldContain "data class Widget("
                generated shouldContain "enum class Colour { red, green }"
            }
    }

    /** A relative `$ref` in a fetched document is relative to *it*, not to the disk. */
    @Test
    fun `a relative reference in a fetched document is read against that document`(@TempDir dir: File) {
        Stub()
            .serving("/schemas/common.yaml", widgetReferringSideways)
            .serving("/schemas/colour.yaml", colour)
            .use { stub ->
                val document = documentOf(
                    "openapi.yaml" to referring(stub.url("/schemas/common.yaml#/components/schemas/Widget")),
                )
                val options = allowing(stub, dir)
                Import.updateLock(document, options, acceptChanges = false)

                lockfile(dir).readText() shouldContain stub.url("/schemas/colour.yaml")
                imported(document, options) shouldContain "enum class Colour { red, green }"
            }
    }

    // ------------------------------------------------------------ the lockfile

    @Test
    fun `the lockfile says what it is, and is sorted so a diff reads`(@TempDir dir: File) {
        Stub()
            .serving("/z.yaml", widget)
            .serving("/a.yaml", widget)
            .use { stub ->
                val document = documentOf(
                    "openapi.yaml" to referringTwice(
                        stub.url("/z.yaml#/components/schemas/Widget"),
                        stub.url("/a.yaml#/components/schemas/Widget"),
                    ),
                )
                Import.updateLock(document, allowing(stub, dir), acceptChanges = false)

                val text = lockfile(dir).readText()
                text shouldContain "# Pelican remote reference lock. Commit this file."
                text shouldContain "updateTestEndpointsLock"

                val urls = text.lines().filterNot { it.startsWith("#") || it.isBlank() }.map { it.substringBefore(' ') }
                urls shouldBe listOf(stub.url("/a.yaml"), stub.url("/z.yaml"))
                // One document served at two URLs is one hash: the record is of
                // what came back, so identical bytes are identical entries.
                text.lines().filter { it.contains("sha256:") && !it.startsWith("#") }
                    .map { it.substringAfter("sha256:") }.distinct().size shouldBe 1
            }
    }

    @Test
    fun `a URL nothing recorded is not fetched`(@TempDir dir: File) {
        Stub().serving("/common.yaml", widget).use { stub ->
            val document = documentOf("openapi.yaml" to referring(stub.url("/common.yaml#/components/schemas/Widget")))

            val message = shouldThrow<ImportFailure> { imported(document, allowing(stub, dir)) }.message.orEmpty()
            message shouldContain "does not record it"
            message shouldContain "updateTestEndpointsLock"
        }
    }

    /**
     * The whole point, in one test. Somebody else edited the document behind a
     * URL this build reads; the build fails naming both hashes, rather than
     * generating a different client and saying nothing.
     */
    @Test
    fun `a document that has changed under a recorded hash fails loudly`(@TempDir dir: File) {
        Stub().serving("/common.yaml", widget).use { stub ->
            val document = documentOf("openapi.yaml" to referring(stub.url("/common.yaml#/components/schemas/Widget")))
            val options = allowing(stub, dir)
            Import.updateLock(document, options, acceptChanges = false)

            // The cache is the pinned copy, so it has to go for the far end to
            // be asked again at all — which is itself the offline story, seen
            // from the other side.
            cache(dir).deleteRecursively()
            stub.serving("/common.yaml", widgetWithExtra)

            val message = shouldThrow<ImportFailure> { imported(document, options) }.message.orEmpty()
            message shouldContain "is not what it was when the lockfile was written"
            message shouldContain "recorded  sha256:"
            message shouldContain "fetched   sha256:"
            message shouldContain "--accept-changes"
        }
    }

    @Test
    fun `a cached copy somebody edited is caught by the hash it is named after`(@TempDir dir: File) {
        Stub().serving("/common.yaml", widget).use { stub ->
            val document = documentOf("openapi.yaml" to referring(stub.url("/common.yaml#/components/schemas/Widget")))
            val options = allowing(stub, dir)
            Import.updateLock(document, options, acceptChanges = false)

            cache(dir).listFiles().orEmpty().single().appendText("\n# edited\n")

            val message = shouldThrow<ImportFailure> { imported(document, options) }.message.orEmpty()
            message shouldContain "is not what it is named after"
            message shouldContain "delete it and run"
        }
    }

    @Test
    fun `a lockfile line that is not a lockfile line says so`(@TempDir dir: File) {
        Stub().serving("/common.yaml", widget).use { stub ->
            val document = documentOf("openapi.yaml" to referring(stub.url("/common.yaml#/components/schemas/Widget")))
            val options = allowing(stub, dir)
            Import.updateLock(document, options, acceptChanges = false)
            lockfile(dir).appendText("${stub.url("/common.yaml")} md5:whatever\n")

            shouldThrow<ImportFailure> { imported(document, options) }
                .message.orEmpty() shouldContain "is not a lockfile entry"
        }
    }

    // ------------------------------------------------------------ updating

    @Test
    fun `the update reports what it added, and refuses to change what it already held`(@TempDir dir: File) {
        Stub().serving("/common.yaml", widget).use { stub ->
            val document = documentOf("openapi.yaml" to referring(stub.url("/common.yaml#/components/schemas/Widget")))
            val options = allowing(stub, dir)

            val first = Import.updateLock(document, options, acceptChanges = false)
            first.first() shouldContain "+ ${stub.url("/common.yaml")} sha256:"
            first.last() shouldContain "Wrote ${lockfile(dir)}"

            stub.serving("/common.yaml", widgetWithExtra)
            val refused = shouldThrow<ImportFailure> {
                Import.updateLock(document, options, acceptChanges = false)
            }.message.orEmpty()
            refused shouldContain "1 recorded document(s) have changed"
            refused shouldContain "was sha256:"
            refused shouldContain "now sha256:"
            refused shouldContain "--accept-changes"

            val accepted = Import.updateLock(document, options, acceptChanges = true)
            accepted.first() shouldContain "~ ${stub.url("/common.yaml")} sha256:"
        }
    }

    /** A cached copy nothing points at any more goes, so the directory is what the lockfile says. */
    @Test
    fun `the cache holds what the lockfile holds and nothing else`(@TempDir dir: File) {
        Stub().serving("/common.yaml", widget).use { stub ->
            val document = documentOf("openapi.yaml" to referring(stub.url("/common.yaml#/components/schemas/Widget")))
            val options = allowing(stub, dir)
            Import.updateLock(document, options, acceptChanges = false)

            stub.serving("/common.yaml", widgetWithExtra)
            Import.updateLock(document, options, acceptChanges = true)

            cache(dir).listFiles().orEmpty().size shouldBe 1
            lockfile(dir).readText() shouldContain cache(dir).listFiles().orEmpty().single().name.removeSuffix(".yaml")
        }
    }

    @Test
    fun `updating an entry that allows nothing says what to write first`(@TempDir dir: File) {
        val document = documentOf("openapi.yaml" to referring("https://schemas.test/common.yaml"))
        val options = ImportOptions("test", "test", lockfile = lockfile(File(document.parent)))

        shouldThrow<ImportFailure> { Import.updateLock(document, options, acceptChanges = false) }
            .message.orEmpty() shouldContain "allowRemote(\"https://…\")"
    }

    // ------------------------------------------------------------ offline

    /**
     * The case CI is. The lockfile and the cache are both committed, the
     * machine has no network, and the build generates the same code it did
     * yesterday without opening a socket.
     */
    @Test
    fun `a checkout with the lockfile and the cache builds with no host to reach`(@TempDir dir: File) {
        val stub = Stub().serving("/common.yaml", widget)
        val document = documentOf("openapi.yaml" to referring(stub.url("/common.yaml#/components/schemas/Widget")))
        val options = allowing(stub, dir)
        Import.updateLock(document, options, acceptChanges = false)

        // Not `use`: the point is that the host is gone by the time the import
        // runs, and the import does not notice.
        stub.close()

        imported(document, options) shouldContain "data class Widget("
    }

    @Test
    fun `a host that cannot be reached says what a checked-in cache would have bought`(@TempDir dir: File) {
        val stub = Stub().serving("/common.yaml", widget)
        val document = documentOf("openapi.yaml" to referring(stub.url("/common.yaml#/components/schemas/Widget")))
        val options = allowing(stub, dir)
        Import.updateLock(document, options, acceptChanges = false)
        cache(dir).deleteRecursively()
        stub.close()

        val message = shouldThrow<ImportFailure> { imported(document, options) }.message.orEmpty()
        message shouldContain "could not be reached"
        message shouldContain "needs no network at all"
    }

    // ------------------------------------------------------------ refusals

    @Test
    fun `a host the build file did not name is not fetched`(@TempDir dir: File) {
        val document = documentOf("openapi.yaml" to referring("https://elsewhere.test/common.yaml"))
        val options = ImportOptions(
            "test",
            "test",
            allowRemote = setOf("https://schemas.test"),
            lockfile = lockfile(dir),
        )

        val message = shouldThrow<ImportFailure> { imported(document, options) }.message.orEmpty()
        message shouldContain "does not allow that host"
        message shouldContain "https://schemas.test"
        message shouldContain "allowRemote(\"https://elsewhere.test\")"
    }

    @Test
    fun `plain HTTP to an allowed host has to be asked for by name`(@TempDir dir: File) {
        val document = documentOf("openapi.yaml" to referring("http://schemas.test/common.yaml"))
        val options = ImportOptions(
            "test",
            "test",
            allowRemote = setOf("schemas.test"),
            lockfile = lockfile(dir),
        )

        val message = shouldThrow<ImportFailure> { imported(document, options) }.message.orEmpty()
        message shouldContain "which is plain HTTP"
        message shouldContain "allowRemote(\"http://schemas.test\")"
    }

    @Test
    fun `a scheme that is not HTTP at all is not fetched`(@TempDir dir: File) {
        val document = documentOf("openapi.yaml" to referring("ftp://schemas.test/common.yaml"))
        val options = ImportOptions(
            "test",
            "test",
            allowRemote = setOf("schemas.test"),
            lockfile = lockfile(dir),
        )

        shouldThrow<ImportFailure> { imported(document, options) }
            .message.orEmpty() shouldContain "is a `ftp:` URL"
    }

    @Test
    fun `a credential written into the URL is refused rather than committed`(@TempDir dir: File) {
        val document = documentOf("openapi.yaml" to referring("https://user:secret@schemas.test/common.yaml"))
        val options = ImportOptions(
            "test",
            "test",
            allowRemote = setOf("schemas.test"),
            lockfile = lockfile(dir),
        )

        val message = shouldThrow<ImportFailure> { imported(document, options) }.message.orEmpty()
        message shouldContain "carrying a credential in it"
        // Whatever else this says, it does not say the password back. A refusal
        // is read in a console, a CI log and an issue somebody pastes it into.
        message shouldNotContain "secret"
    }

    @Test
    fun `a server answering something other than 200 says so`(@TempDir dir: File) {
        Stub().answering("/common.yaml", GONE).use { stub ->
            val document = documentOf("openapi.yaml" to referring(stub.url("/common.yaml#/components/schemas/Widget")))

            shouldThrow<ImportFailure> {
                Import.updateLock(document, allowing(stub, dir), acceptChanges = false)
            }.message.orEmpty() shouldContain "the server answered 410"
        }
    }

    /**
     * The failure the allowlist would otherwise be worth nothing without: the
     * reviewed host answers a redirect, and the document a build reads comes
     * from wherever that points.
     */
    @Test
    fun `a redirect is not followed, to another host or to this one`(@TempDir dir: File) {
        Stub()
            .answering("/common.yaml", FOUND, mapOf("Location" to "https://elsewhere.test/common.yaml"))
            .answering("/near.yaml", FOUND, mapOf("Location" to "/common.yaml"))
            .use { stub ->
                val away = documentOf(
                    "openapi.yaml" to referring(stub.url("/common.yaml#/components/schemas/Widget")),
                )
                val elsewhere = shouldThrow<ImportFailure> {
                    Import.updateLock(away, allowing(stub, dir), acceptChanges = false)
                }.message.orEmpty()
                elsewhere shouldContain "answered 302 pointing at https://elsewhere.test/common.yaml"
                elsewhere shouldContain "Redirects are not followed"
                elsewhere shouldContain "allowRemote"

                val near = documentOf("openapi.yaml" to referring(stub.url("/near.yaml#/components/schemas/Widget")))
                shouldThrow<ImportFailure> {
                    Import.updateLock(near, allowing(stub, dir), acceptChanges = false)
                }.message.orEmpty() shouldContain "one this build allows — write the URL it gave into the `\$ref`"
            }
    }

    @Test
    fun `something that is not a document is named as what it is`(@TempDir dir: File) {
        Stub().serving("/common.yaml", "<html><body>Sign in to continue</body></html>", "text/html").use { stub ->
            val document = documentOf("openapi.yaml" to referring(stub.url("/common.yaml#/components/schemas/Widget")))

            val message = shouldThrow<ImportFailure> {
                Import.updateLock(document, allowing(stub, dir), acceptChanges = false)
            }.message.orEmpty()
            message shouldContain "what came back is not a document"
            message shouldContain "does not hold an object at its root"
        }
    }

    /**
     * A fragment of a fetched document is the ordinary case — the whole file
     * is a library and the `$ref` names one schema in it. A fragment naming
     * nothing fails the same way it does for a file on disk, and it fails
     * during the update as well, which is the point: the lockfile is not
     * written from a document nobody could read.
     */
    @Test
    fun `a pointer into a fetched document that is not there names the pointer`(@TempDir dir: File) {
        Stub().serving("/common.yaml", widget).use { stub ->
            val document = documentOf("openapi.yaml" to referring(stub.url("/common.yaml#/components/schemas/Gadget")))

            shouldThrow<ImportFailure> {
                Import.updateLock(document, allowing(stub, dir), acceptChanges = false)
            }.message.orEmpty() shouldContain "there is nothing at that pointer"
            lockfile(dir).exists() shouldBe false
        }
    }

    @Test
    fun `allowing a host with no lockfile to record it in is refused`(@TempDir dir: File) {
        val document = documentOf("openapi.yaml" to referring("https://schemas.test/common.yaml"))
        val options = ImportOptions("test", "test", allowRemote = setOf("schemas.test"))

        shouldThrow<ImportFailure> { imported(document, options) }
            .message.orEmpty() shouldContain "no lockfile was given"
        dir.isDirectory shouldBe true
    }

    @Test
    fun `an allowRemote entry that is not an origin says what one looks like`(@TempDir dir: File) {
        val document = documentOf("openapi.yaml" to referring("https://schemas.test/common.yaml"))

        shouldThrow<ImportFailure> {
            imported(
                document,
                ImportOptions(
                    "test",
                    "test",
                    allowRemote = setOf("https://schemas.test/specs"),
                    lockfile = lockfile(dir),
                ),
            )
        }.message.orEmpty() shouldContain "names a URL. What is allowed is a host"
    }

    // ------------------------------------------------------------

    private fun allowing(stub: Stub, dir: File) =
        ImportOptions("test", "test", allowRemote = setOf(stub.origin), lockfile = lockfile(dir))

    private fun lockfile(dir: File) = File(dir, "test.refs.lock")

    private fun cache(dir: File) = File(dir, "test.refs.lock.d")

    private fun referring(ref: String) = """
        openapi: 3.1.0
        info: { title: T, version: "1" }
        paths:
          /widgets:
            get:
              operationId: listWidgets
              responses:
                "200":
                  description: ok
                  content:
                    application/json:
                      schema: { ${'$'}ref: '$ref' }
    """.trimIndent()

    private fun referringTwice(first: String, second: String) = """
        openapi: 3.1.0
        info: { title: T, version: "1" }
        paths:
          /widgets:
            get:
              operationId: listWidgets
              responses:
                "200":
                  description: ok
                  content:
                    application/json:
                      schema: { ${'$'}ref: '$first' }
          /gadgets:
            get:
              operationId: listGadgets
              responses:
                "200":
                  description: ok
                  content:
                    application/json:
                      schema: { ${'$'}ref: '$second' }
    """.trimIndent()

    private val widget = """
        components:
          schemas:
            Widget:
              type: object
              required: [id]
              properties:
                id: { type: string }
    """

    private val widgetWithExtra = """
        components:
          schemas:
            Widget:
              type: object
              required: [id]
              properties:
                id: { type: string }
                weight: { type: integer, format: int32 }
    """

    private val widgetWithColour = """
        components:
          schemas:
            Widget:
              type: object
              required: [id]
              properties:
                id: { type: string }
                colour: { ${'$'}ref: './colour.yaml#/components/schemas/Colour' }
    """

    private val widgetReferringSideways = widgetWithColour

    private val colour = """
        components:
          schemas:
            Colour:
              type: string
              enum: [red, green]
    """

    private companion object {
        const val GONE = 410
        const val FOUND = 302
    }
}
