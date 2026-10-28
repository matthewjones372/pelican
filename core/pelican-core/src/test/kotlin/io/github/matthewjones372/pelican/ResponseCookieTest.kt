package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A `Set-Cookie` written from a value with safe defaults, refusing the combinations a browser would mishandle. */
class ResponseCookieTest {

    @Test
    fun `the defaults are HttpOnly, Secure, SameSite=Lax and the whole site`() {
        ResponseCookie("sid", "abc").headerValue shouldBe "sid=abc; Path=/; HttpOnly; Secure; SameSite=Lax"
    }

    @Test
    fun `a lifetime is Max-Age in whole seconds, and a negative one is already over`() {
        ResponseCookie("sid", "abc", maxAge = 10.minutes).headerValue shouldBe
            "sid=abc; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=600"
        ResponseCookie("sid", "abc", maxAge = (-5).seconds).headerValue shouldContain "Max-Age=0"
    }

    @Test
    fun `an expired cookie is empty and ends now`() {
        ResponseCookie.expired("sid").headerValue shouldBe "sid=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0"
    }

    @Test
    fun `every attribute can be said, in the order a reader expects`() {
        ResponseCookie(
            "prefs",
            "dark",
            path = "/app",
            domain = "example.com",
            httpOnly = false,
            sameSite = SameSite.STRICT,
            maxAge = 1.seconds,
        ).headerValue shouldBe "prefs=dark; Path=/app; Domain=example.com; Secure; SameSite=Strict; Max-Age=1"
    }

    @Test
    fun `SameSite=None without Secure is refused, since a browser drops it`() {
        shouldThrow<IllegalArgumentException> {
            ResponseCookie("sid", "abc", secure = false, sameSite = SameSite.NONE)
        }.message shouldContain "Secure"
        ResponseCookie("sid", "abc", sameSite = SameSite.NONE).headerValue shouldContain "SameSite=None"
    }

    @Test
    fun `a __Host- cookie is Secure, for the whole site, and names no Domain`() {
        shouldThrow<IllegalArgumentException> { ResponseCookie("__Host-sid", "abc", domain = "example.com") }
        shouldThrow<IllegalArgumentException> { ResponseCookie("__Host-sid", "abc", path = "/app") }
        shouldThrow<IllegalArgumentException> { ResponseCookie("__Host-sid", "abc", secure = false) }
        ResponseCookie("__Host-sid", "abc").headerValue shouldContain "__Host-sid=abc"
    }

    @Test
    fun `a __Secure- cookie is Secure`() {
        shouldThrow<IllegalArgumentException> { ResponseCookie("__Secure-sid", "abc", secure = false) }
    }

    @Test
    fun `a name or value that would break the header is refused`() {
        shouldThrow<IllegalArgumentException> { ResponseCookie("s id", "abc") }
        shouldThrow<IllegalArgumentException> { ResponseCookie("sid=", "abc") }
        shouldThrow<IllegalArgumentException> { ResponseCookie("", "abc") }
        shouldThrow<IllegalArgumentException> { ResponseCookie("sid", "a;b") }
        shouldThrow<IllegalArgumentException> { ResponseCookie("sid", "a b") }
        shouldThrow<IllegalArgumentException> { ResponseCookie("sid", "abc", path = "/a;b") }
    }

    // ------------------------------------------------------------ set from a handler

    private val setCookieHeader = responseHeader<String>("Set-Cookie")

    private val signIn = endpoint {
        post("sign-in")
        emits(setCookieHeader)
        text()
    }

    private val undeclared = endpoint {
        post("quiet")
        text()
    }

    private fun send(ep: Endpoint<*, *>, handle: Params.() -> Unit): ClientResponse {
        val api = api(
            listOf(
                ServerEndpoint(ep) { p ->
                    p.handle()
                    CompletableFuture.completedStage("ok" as Any?)
                },
            ),
        )
        return InMemoryClientTransport(api)
            .send(ClientRequest(Method.POST, "http://test${ep.pathSpec.template}", emptyList()))
            .toCompletableFuture().join()
    }

    @Test
    fun `two cookies are two Set-Cookie headers, and a second by the same name replaces the first`() {
        val answered = send(signIn) {
            setCookie(ResponseCookie("sid", "first"))
            setCookie(ResponseCookie.expired("sign_in"))
            setCookie(ResponseCookie("sid", "second"))
        }

        val cookies = answered.headers.filter { it.first.equals("Set-Cookie", ignoreCase = true) }.map { it.second }

        cookies shouldContainExactly listOf(
            "sid=second; Path=/; HttpOnly; Secure; SameSite=Lax",
            "sign_in=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0",
        )
    }

    /** How a replayed response puts back what it kept: two cookies stay two, as they went out the first time. */
    @Test
    fun `a raw Set-Cookie is another header rather than the last one replaced`() {
        val answered = send(signIn) {
            setRawHeader("Set-Cookie", "a=1; Path=/")
            setRawHeader("Set-Cookie", "b=2; Path=/")
        }

        answered.headers.filter { it.first == "Set-Cookie" }.map { it.second } shouldContainExactly
            listOf("a=1; Path=/", "b=2; Path=/")
    }

    @Test
    fun `a declared Set-Cookie set twice is two headers`() {
        val answered = send(signIn) {
            setHeader(setCookieHeader, "a=1; Path=/")
            setHeader(setCookieHeader, "b=2; Path=/")
        }

        answered.headers.filter { it.first == "Set-Cookie" }.map { it.second } shouldContainExactly
            listOf("a=1; Path=/", "b=2; Path=/")
    }

    @Test
    fun `cookies go out in the order they were set, whichever call set them`() {
        val answered = send(signIn) {
            addRawHeader("Set-Cookie", "device=d1; Path=/")
            setCookie(ResponseCookie("sid", "abc"))
            setHeader(setCookieHeader, "theme=dark; Path=/")
        }

        answered.headers.filter { it.first == "Set-Cookie" }.map { it.second } shouldContainExactly listOf(
            "device=d1; Path=/",
            "sid=abc; Path=/; HttpOnly; Secure; SameSite=Lax",
            "theme=dark; Path=/",
        )
    }

    @Test
    fun `addRawHeader appends under any name, and setRawHeader replaces every value of it`() {
        val appended = send(undeclared) {
            setRawHeader("Link", "</a>; rel=preload")
            addRawHeader("Link", "</b>; rel=preload")
        }
        appended.headers.filter { it.first == "Link" }.map { it.second } shouldContainExactly
            listOf("</a>; rel=preload", "</b>; rel=preload")

        val replaced = send(undeclared) {
            addRawHeader("Link", "</a>; rel=preload")
            addRawHeader("Link", "</b>; rel=preload")
            setRawHeader("link", "</c>; rel=preload")
        }
        replaced.headers.filter { it.first.equals("Link", ignoreCase = true) }.map { it.second } shouldContainExactly
            listOf("</c>; rel=preload")
    }

    @Test
    fun `an endpoint that never declared Set-Cookie cannot send one`() {
        send(undeclared) { setCookie(ResponseCookie("sid", "abc")) }.status shouldBe 500
    }
}
