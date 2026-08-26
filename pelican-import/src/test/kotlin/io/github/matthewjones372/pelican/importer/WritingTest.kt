package io.github.matthewjones372.pelican.importer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WritingTest {

    private val document = File("src/test/resources/bookmarks.yaml")

    @Test
    fun `files land under the package they declare`(@TempDir root: File) {
        val written = Import.write(document, importOptions("com.example.marks", "bookmarks"), root)

        written shouldBe listOf(File(root, "com/example/marks/BookmarksEndpoints.kt"))
        written.single().readText() shouldContain "package com.example.marks"
    }

    @Test
    fun `the endpoints file is rewritten and the handlers are not`(@TempDir root: File) {
        val options = importOptions("app", "bookmarks") { handlers = Backend.HTTP4K }
        Import.write(document, options, root)

        val endpoints = File(root, "app/BookmarksEndpoints.kt")
        val handlers = File(root, "app/BookmarksHandlers.kt")
        endpoints.writeText("// stale")
        handlers.writeText("// mine, with a handler in it")

        val second = Import.write(document, options, root)

        second shouldBe listOf(endpoints)
        endpoints.readText() shouldContain "val getBookmark = endpoint(bookmarkId)"
        handlers.readText() shouldBe "// mine, with a handler in it"
    }

    @Test
    fun `a backend that does not exist is named rather than guessed at`(@TempDir root: File) {
        shouldThrow<ImportFailure> {
            importEndpoints(document, root, "app", "bookmarks", emptySet(), "vertx")
        }.message.orEmpty() shouldContain "No backend called 'vertx'"
    }

    @Test
    fun `a missing document says which one`(@TempDir root: File) {
        shouldThrow<ImportFailure> {
            Import.write(File(root, "nothing.yaml"), importOptions("app", "x"), root)
        }.message.orEmpty() shouldContain "No such file"
    }

    @Test
    fun `no server library and no document emitter are on this module's classpath`() {
        withClue("pelican-import generates source; a server here would mean a dependency crept in") {
            shouldThrow<ClassNotFoundException> { Class.forName("org.apache.pekko.http.javadsl.server.Directives") }
            shouldThrow<ClassNotFoundException> { Class.forName("io.github.matthewjones372.pelican.openapi.OpenApiKt") }
        }
    }
}
