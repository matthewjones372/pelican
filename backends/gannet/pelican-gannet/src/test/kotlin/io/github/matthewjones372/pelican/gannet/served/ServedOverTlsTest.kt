package io.github.matthewjones372.pelican.gannet.served

import io.github.matthewjones372.gannet.GannetConfig
import io.github.matthewjones372.gannet.GannetTls
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.gannet.RequiresGannet
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.start
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

private val ping = endpoint {
    get("ping")
    text()
}

@RequiresGannet
class ServedOverTlsTest {

    @Test
    fun `an api started with tls in its config answers over https at its baseUrl`() {
        val tls = GannetTls(keyStore, PASSWORD)
        api(endpoints = listOf(ping handledNow { "pong" })).start(port = 0, config = GannetConfig(tls = tls)).use {
            it.baseUrl shouldStartWith "https://127.0.0.1:"

            val client = HttpClient.newBuilder().sslContext(trusting(keyStore)).build()
            val res = client.send(
                HttpRequest.newBuilder(URI.create("${it.baseUrl}/ping")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            res.statusCode() shouldBe 200
            res.body() shouldBe "pong"
        }
    }

    @Test
    fun `and without tls the baseUrl is plain http`() {
        api(endpoints = listOf(ping handledNow { "pong" })).start(port = 0).use {
            it.baseUrl shouldStartWith "http://127.0.0.1:"
        }
    }

    private companion object {
        val PASSWORD: CharArray = "pelican-test".toCharArray()

        /** A self-signed certificate for 127.0.0.1, made with the JDK's keytool. */
        val keyStore: Path by lazy {
            val file = Files.createTempDirectory("pelican-tls").resolve("server.p12")
            val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
            val process = ProcessBuilder(
                keytool, "-genkeypair", "-alias", "pelican", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", file.toString(),
                "-storepass", String(PASSWORD), "-keypass", String(PASSWORD),
            ).redirectErrorStream(true).start()
            val output = process.inputStream.readAllBytes().decodeToString()
            check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool failed: $output" }
            file.toFile().deleteOnExit()
            file
        }

        fun trusting(store: Path): SSLContext {
            val server = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(store).use { load(it, PASSWORD) } }
            val anchors = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setCertificateEntry("pelican", server.getCertificate("pelican"))
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trust.init(anchors)
            return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        }
    }
}
