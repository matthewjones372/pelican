package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.Authenticator
import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.Unauthenticated
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.matchers.shouldBe
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.http.javadsl.model.headers.RawHeader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.atomic.AtomicInteger

/** The caller on Pekko: refused before the handler, and handed to it when verified. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallerTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoRouteTestKit("pelican-caller-test")
    }

    private val bearer = bearerAuth()
    private val caller = authenticated(bearer) { it.subject }
    private val ran = AtomicInteger()

    private val whoAmI = endpoint {
        get("me")
        authenticatedBy(caller)
        text()
    }

    private val api = api(
        endpoints = listOf(whoAmI handledNow { _ -> ran.incrementAndGet(); "you are ${this[caller]}" }),
        codecs = JacksonCodecs,
    ) {
        authenticate(
            bearer,
            Authenticator { c ->
                c.bearerToken()?.let { if (it == "ada") Identity("ada") else throw Unauthenticated("unknown") }
            },
        )
    }

    private val route by lazy { pekko.testRoute(api.toRoute(pekko.system())) }

    @Test
    fun `no credential is a 401 with a challenge, and the handler never runs`() {
        val before = ran.get()
        val res = route.run(HttpRequest.GET("/me"))

        res.assertStatusCode(401)
        res.response().getHeader("WWW-Authenticate").map { it.value() }.orElse(null) shouldBe "Bearer"
        ran.get() shouldBe before
    }

    @Test
    fun `a verified caller reaches the handler`() {
        route.run(HttpRequest.GET("/me").addHeader(RawHeader.create("Authorization", "Bearer ada")))
            .assertStatusCode(200)
            .assertEntity("you are ada")
    }

    @Test
    fun `a credential that does not verify is a 401 too`() {
        route.run(HttpRequest.GET("/me").addHeader(RawHeader.create("Authorization", "Bearer eve")))
            .assertStatusCode(401)
    }
}
