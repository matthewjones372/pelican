package io.github.matthewjones372.pelican.uring.served

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.pages
import io.github.matthewjones372.pelican.uring.RequiresUring
import io.github.matthewjones372.pelican.uring.handledNow
import io.github.matthewjones372.pelican.uring.start
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Pages packed in a jar, as a service ships them, answer as pages from a directory do. */
@RequiresUring
class PagesFromAJarTest {

    private val ping = endpoint {
        get("ping")
        text()
    }

    private fun jar(dir: Path): Path {
        val jar = dir.resolve("pages.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            listOf(
                "jar-pages/" to null,
                "jar-pages/index.html" to "<title>Packed</title>",
                "jar-pages/sub/" to null,
                "jar-pages/sub/index.html" to "<title>Packed below</title>",
                "jar-secret.txt" to "not a page",
            ).forEach { (name, text) ->
                out.putNextEntry(JarEntry(name))
                text?.let { out.write(it.toByteArray()) }
                out.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `a jar's pages, directories and validators answer, and nothing outside it does`(@TempDir dir: Path) {
        val loader = URLClassLoader(arrayOf(jar(dir).toUri().toURL()), javaClass.classLoader)
        val thread = Thread.currentThread()
        val before = thread.contextClassLoader
        thread.contextClassLoader = loader
        val server = try {
            api(listOf(ping handledNow { "pong" }), JacksonCodecs) { pages = pages("jar-pages") }.start(port = 0)
        } finally {
            thread.contextClassLoader = before
        }
        try {
            Asking(listOf("Location", "Content-Type", "ETag")).use { asking ->
                val home = asking.ask(server.baseUrl, "GET", "/")
                home.status shouldBe 200
                home.body shouldBe "<title>Packed</title>"
                home.headers["Content-Type"] shouldBe "text/html; charset=UTF-8"

                asking.ask(server.baseUrl, "GET", "/sub").let {
                    it.status shouldBe 301
                    it.headers["Location"] shouldBe "/sub/"
                }
                asking.ask(server.baseUrl, "GET", "/sub/").body shouldBe "<title>Packed below</title>"

                val tag = checkNotNull(home.headers["ETag"])
                asking.ask(server.baseUrl, "GET", "/", mapOf("If-None-Match" to tag)).status shouldBe 304

                asking.ask(server.baseUrl, "GET", "/missing").status shouldBe 404
                listOf("/..%2Fjar-secret.txt", "/sub/..%2F..%2Fjar-secret.txt", "/%5C..%5Cjar-secret.txt").forEach {
                    val escape = asking.ask(server.baseUrl, "GET", it)
                    escape.status shouldBe 404
                    escape.body shouldNotContain "not a page"
                }
                asking.ask(server.baseUrl, "GET", "/ping").body shouldBe "pong"
            }
        } finally {
            server.stop()
            loader.close()
        }
    }
}
