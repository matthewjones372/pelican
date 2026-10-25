package io.github.matthewjones372.pelican.gannet.served

import io.github.matthewjones372.pelican.Authenticator
import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.Unauthenticated
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.queryParam
import io.github.matthewjones372.pelican.gannet.RequiresUring
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.start
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.atomic.AtomicInteger
import io.github.matthewjones372.pelican.jetty.handledNow as jettyHandledNow
import io.github.matthewjones372.pelican.jetty.start as jettyStart

/** The caller on both servers (spec 0061): refused before the handler, and handed to it when verified. */
@RequiresUring
class CallersCheckedAsJettyChecksThemTest {

    /** One request, named so a failing case says which. */
    data class Asked(val name: String, val path: String, val headers: Map<String, String> = emptyMap()) {
        override fun toString() = name
    }

    companion object {
        private val bearer = bearerAuth()

        /** A bearer token, or else a `session` cookie, as `bearerOrSession` reads one. */
        private val verifier = Authenticator { c ->
            when (val presented = c.bearerToken() ?: c.cookie("session")) {
                null -> null
                "ada", "grace" -> Identity(presented)
                else -> throw Unauthenticated("unknown")
            }
        }

        private val caller = authenticated(bearer) { it.subject }
        private val maybe = caller.optional()

        private val whoAmI = endpoint {
            get("me")
            authenticatedBy(caller)
            text()
        }

        private val greeting = endpoint {
            get("hello")
            authenticatedBy(maybe)
            text()
        }

        private val counted = endpoint(queryParam<Int>("n")) {
            get("count")
            authenticatedBy(caller)
            json<Int>()
        }

        private val ran = AtomicInteger()

        private fun served(routes: List<ServerEndpoint>) =
            api(routes, JacksonCodecs) { authenticate(bearer, verifier) }

        private val uring = served(
            listOf(
                whoAmI handledNow { ran.incrementAndGet(); "you are ${this[caller]}" },
                greeting handledNow { "hello ${this[maybe] ?: "stranger"}" },
                counted handledNow { n -> ran.incrementAndGet(); n },
            ),
        ).start(port = 0)

        private val jetty = served(
            listOf(
                whoAmI jettyHandledNow { ran.incrementAndGet(); "you are ${this[caller]}" },
                greeting jettyHandledNow { "hello ${this[maybe] ?: "stranger"}" },
                counted jettyHandledNow { n -> ran.incrementAndGet(); n },
            ),
        ).jettyStart(port = 0)

        private val asking = Asking(listOf("Content-Type", "WWW-Authenticate"))

        @JvmStatic
        fun cases(): List<Asked> = listOf(
            Asked("no credential", "/me"),
            Asked("a verified bearer", "/me", mapOf("Authorization" to "Bearer ada")),
            Asked("the scheme in another case", "/me", mapOf("authorization" to "bearer ada")),
            Asked("a bearer that does not verify", "/me", mapOf("Authorization" to "Bearer eve")),
            Asked("a session cookie", "/me", mapOf("Cookie" to "theme=dark; session=grace")),
            Asked("a session cookie that does not verify", "/me", mapOf("Cookie" to "session=eve")),
            Asked("an optional caller absent", "/hello"),
            Asked("an optional caller present", "/hello", mapOf("Authorization" to "Bearer grace")),
            Asked("an optional caller that does not verify", "/hello", mapOf("Authorization" to "Bearer eve")),
            Asked("the caller is checked before a bad query", "/count?n=many"),
            Asked("the caller is checked before negotiation", "/me", mapOf("Accept" to "image/png")),
            Asked("a verified caller has its query read", "/count?n=many", mapOf("Authorization" to "Bearer ada")),
        )

        @JvmStatic
        @AfterAll
        fun stop() {
            asking.close()
            uring.stop()
            jetty.stop()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `uring checks the caller as jetty does`(asked: Asked) {
        val onUring = asking.ask(uring.baseUrl, "GET", asked.path, asked.headers)
        val onJetty = asking.ask(jetty.baseUrl, "GET", asked.path, asked.headers)
        withClue("${asked.name}: uring $onUring, jetty $onJetty") { onUring shouldBe onJetty }
    }

    @Test
    fun `a refused caller never reaches the handler`() {
        val before = ran.get()
        asking.ask(uring.baseUrl, "GET", "/me").status shouldBe 401
        asking.ask(uring.baseUrl, "GET", "/count?n=1", mapOf("Cookie" to "session=eve")).status shouldBe 401
        ran.get() shouldBe before
    }
}
