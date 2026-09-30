package io.github.matthewjones372.pelican.oidc

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.github.matthewjones372.pelican.Credentials
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.pages
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.start
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.CompletableFuture

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignInTest {

    private val provider = StubProvider(clientId = "bank-pages")
    private val port = ServerSocket(0).use { it.localPort }
    private val base = "http://127.0.0.1:$port"

    private val pocketId = oidc(provider.issuer, audience = "lark-bank")
    private val signIn = pocketId.signIn(
        clientId = "bank-pages",
        callbackUrl = "$base/callback",
        sessionKey = ByteArray(32) { it.toByte() },
    )

    private val caller = authenticated(pocketId.scheme) { it.name ?: it.subject }
    private val me = endpoint {
        get("me")
        authenticatedBy(caller)
        text()
    }

    private val whoIsThere = authenticated(pocketId.scheme) { "${it.subject} by ${it.actor ?: "themselves"}" }
    private val acting = endpoint {
        get("acting")
        authenticatedBy(whoIsThere)
        text()
    }

    private val server: PelicanServer = api(
        signIn.endpoints +
            ServerEndpoint(me) { p -> CompletableFuture.completedStage(p[caller] as Any?) } +
            ServerEndpoint(acting) { p -> CompletableFuture.completedStage(p[whoIsThere] as Any?) },
        codecs = JacksonCodecs,
    ) {
        authenticate(pocketId.scheme, signIn.authenticator)
        pages = pages("signed-in").guardedBy(signIn.guard)
    }.start(port = port)

    @AfterAll
    fun stop() {
        server.stop()
        provider.close()
    }

    /**
     * Headless Chromium. `PLAYWRIGHT_CHROMIUM_EXECUTABLE` names one already
     * installed, for a machine whose browsers are not the build this Playwright
     * expects; without it Playwright uses, or fetches, its own.
     */
    private fun inBrowser(block: (Page) -> Unit) {
        val options = BrowserType.LaunchOptions()
        System.getenv("PLAYWRIGHT_CHROMIUM_EXECUTABLE")?.let { options.setExecutablePath(Paths.get(it)) }
        Playwright.create().use { playwright ->
            playwright.chromium().launch(options).use { browser ->
                browser.newContext().use { context -> block(context.newPage()) }
            }
        }
    }

    @Test
    fun `a person signs in, sees who they are, signs out, and is sent back to login`() {
        inBrowser(::signInAndOut)
    }

    private fun signInAndOut(page: Page) {
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        page.onRequest { asked += it.url() }

        page.navigate("$base/")
        page.url() shouldStartWith "${provider.issuer}/authorize"
        asked shouldContain "$base/login?return=%2F"

        page.click("#as-ada")
        page.waitForURL("$base/")
        page.waitForSelector("#who:has-text('Ada Lovelace')")

        asked.clear()
        page.click("#sign-out")
        withClue({ "at ${page.url()} having asked for $asked" }) {
            page.waitForURL("${provider.issuer}/authorize**", Page.WaitForURLOptions().setTimeout(5_000.0))
        }
        asked shouldContain "$base/logout"
        asked shouldContain "$base/login?return=%2F"
    }

    private val noRedirects = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    private fun get(path: String, vararg headers: Pair<String, String>): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("$base$path"))
        headers.forEach { (k, v) -> request.header(k, v) }
        return noRedirects.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `login asks for the code with PKCE and a nonce`() {
        val to = get("/login").headers().firstValue("Location").get()

        to shouldStartWith "${provider.issuer}/authorize?"
        to shouldContain "code_challenge_method=S256"
        to shouldContain "nonce="
        to shouldContain "redirect_uri=" + java.net.URLEncoder.encode("$base/callback", Charsets.UTF_8)
    }

    /** `/login`, then the provider's button, then `/callback`, without a browser: where does it land, and with what? */
    private fun signInWithoutABrowser(returnTo: String): HttpResponse<String> {
        val login = get("/login?return=" + java.net.URLEncoder.encode(returnTo, Charsets.UTF_8))
        val cookie = login.headers().firstValue("Set-Cookie").get().substringBefore(';')
        val authorizePage = noRedirects.send(
            HttpRequest.newBuilder(URI.create(login.headers().firstValue("Location").get())).build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        val back = authorizePage.substringAfter("href=\"").substringBefore('"')
        return get(back.removePrefix(base), "Cookie" to cookie)
    }

    @Test
    fun `a sign-in lands where it was asked to, and sets a session the browser cannot read`() {
        val landed = signInWithoutABrowser("/accounts/acc-1")

        landed.statusCode() shouldBe 302
        landed.headers().firstValue("Location").get() shouldBe "/accounts/acc-1"
        val session = landed.headers().firstValue("Set-Cookie").get()
        session shouldStartWith "pelican_session="
        session shouldContain "HttpOnly"
        session shouldContain "Secure"
        session shouldContain "SameSite=Lax"

        get("/me", "Cookie" to session.substringBefore(';')).body() shouldBe "Ada Lovelace"
        provider.tokenRequests.last()["code_verifier"]!!.length shouldBe 43
    }

    @Test
    fun `a return to somewhere else is a return home, so sign-in is no open redirect`() {
        signInWithoutABrowser("//evil.example/x").headers().firstValue("Location").get() shouldBe "/"
        signInWithoutABrowser("https://evil.example/x").headers().firstValue("Location").get() shouldBe "/"
    }

    @Test
    fun `a callback whose state is not the one it started with is refused`() {
        val login = get("/login")
        val cookie = login.headers().firstValue("Set-Cookie").get().substringBefore(';')

        get("/callback?code=anything&state=forged", "Cookie" to cookie).statusCode() shouldBe 400
    }

    @Test
    fun `a code the provider refuses is a 400, not an outage`() {
        val login = get("/login")
        val cookie = login.headers().firstValue("Set-Cookie").get().substringBefore(';')
        val state = login.headers().firstValue("Location").get().substringAfter("state=").substringBefore('&')

        get("/callback?code=never-issued&state=$state", "Cookie" to cookie).statusCode() shouldBe 400
    }

    @Test
    fun `signing out clears the session`() {
        val out = get("/logout")
        out.statusCode() shouldBe 302
        out.headers().firstValue("Set-Cookie").get() shouldStartWith "pelican_session=;"
        out.headers().firstValue("Set-Cookie").get() shouldContain "Max-Age=0"
    }

    @Test
    fun `a callback nobody started is refused`() {
        get("/callback?code=x&state=y").statusCode() shouldBe 400
    }

    @Test
    fun `a session cookie that has been tampered with is nobody`() {
        get("/", "Cookie" to "pelican_session=eyJhbGciOiJkaXIifQ.garbage").statusCode() shouldBe 302
        get("/me", "Cookie" to "pelican_session=eyJhbGciOiJkaXIifQ.garbage").statusCode() shouldBe 401
    }

    @Test
    fun `the API still takes a bearer token instead`() {
        get("/me", "Authorization" to "Bearer not-a-token").statusCode() shouldBe 401
    }

    private fun holding(cookie: String) = object : Credentials {
        override fun header(name: String): String? = null
        override fun cookie(name: String): String? =
            cookie.substringBefore(';').takeIf { it.startsWith("$name=") }?.substringAfter('=')
    }

    private fun signedIn(): String = signInWithoutABrowser("/").headers().firstValue("Set-Cookie").get()

    private fun whoIs(cookie: String): String = get("/acting", "Cookie" to cookie.substringBefore(';')).body()

    @Test
    fun `a session acting as someone is them, with the person signed in as its actor`() {
        val ada = signedIn()
        whoIs(ada) shouldBe "ada by themselves"

        val asCarol = signIn.actAs(holding(ada), "carol", Instant.now().plusSeconds(60))!!
        asCarol shouldContain "HttpOnly"
        whoIs(asCarol) shouldBe "carol by ada"

        whoIs(signIn.stopActing(holding(asCarol))!!) shouldBe "ada by themselves"
    }

    @Test
    fun `acting ends by itself, and the person underneath is still signed in`() {
        val lapsed = signIn.actAs(holding(signedIn()), "carol", Instant.now().minusSeconds(1))!!

        whoIs(lapsed) shouldBe "ada by themselves"
    }

    @Test
    fun `nobody signed in cannot start acting`() {
        signIn.actAs(holding("pelican_session=nothing"), "carol", Instant.now().plusSeconds(60)) shouldBe null
    }
}
