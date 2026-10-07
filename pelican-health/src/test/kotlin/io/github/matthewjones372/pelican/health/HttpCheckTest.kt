package io.github.matthewjones372.pelican.health

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class HttpCheckTest {

    private val downstream = WireMockServer(options().dynamicPort()).also { it.start() }

    @AfterEach
    fun stop() {
        if (downstream.isRunning) downstream.stop()
    }

    private fun answering(status: Int): String {
        downstream.stubFor(get("/health/ready").willReturn(aResponse().withStatus(status)))
        return "${downstream.baseUrl()}/health/ready"
    }

    @Test
    fun `a downstream answering 200 passes`() {
        http(answering(200)) shouldBe Status.Pass
    }

    @Test
    fun `a downstream answering 503 fails, naming the status`() {
        http(answering(503)).shouldBeInstanceOf<Status.Fail>().output shouldContain "503"
    }

    @Test
    fun `a downstream that is not there fails`() {
        val url = answering(200)
        downstream.stop()

        http(url).shouldBeInstanceOf<Status.Fail>().output shouldContain "ConnectException"
    }
}
