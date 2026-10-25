package io.github.matthewjones372.pelican.gannet

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class SupportTest {
    @Test
    fun `this machine can run the runtime`() {
        assumeUring()
        UringSupport.problem().shouldBeNull()
        UringSupport.require()
    }

    @Test
    fun `another OS, an old kernel and another architecture are each named`() {
        UringSupport.platformProblem("Mac OS X", "aarch64", "14.0")!! shouldContain "this is Mac OS X"
        UringSupport.platformProblem("Linux", "amd64", "5.15.0-91-generic")!! shouldContain "this is 5.15.0-91-generic"
        UringSupport.platformProblem("Linux", "riscv64", "6.8")!! shouldContain "riscv64"
        UringSupport.platformProblem("Linux", "amd64", "6.1.0").shouldBeNull()
    }

    @Test
    fun `kernel releases are compared by major and minor`() {
        UringSupport.kernelAtLeast("6.18.44-fc-v153", 6, 1) shouldBe true
        UringSupport.kernelAtLeast("7.0", 6, 1) shouldBe true
        UringSupport.kernelAtLeast("6.0.9", 6, 1) shouldBe false
        UringSupport.kernelAtLeast("garbage", 6, 1) shouldBe false
    }

    @Test
    fun `a configuration out of range is refused`() {
        shouldThrow<IllegalArgumentException> { UringConfig(port = 70_000) }
        shouldThrow<IllegalArgumentException> { UringConfig(carriers = 0) }
        shouldThrow<IllegalArgumentException> { UringConfig(bufferCount = 100) }
        shouldThrow<IllegalArgumentException> { UringConfig(bufferSize = 0) }
        shouldThrow<IllegalArgumentException> { UringConfig(maxRequestLineBytes = 20_000) }
        shouldThrow<IllegalArgumentException> { UringConfig(heldBuffers = 0) }
        shouldThrow<IllegalArgumentException> { UringConfig(tick = java.time.Duration.ZERO) }
    }

    @Test
    fun `headers read without regard to case`() {
        val headers = Headers(listOf("Content-Type" to "a", "X-Y" to "1", "x-y" to "2"))
        headers["content-type"] shouldBe "a"
        headers.all("X-y") shouldBe listOf("1", "2")
        ("CONTENT-TYPE" in headers) shouldBe true
        headers.toString() shouldBe "Headers(Content-Type: a, X-Y: 1, x-y: 2)"
    }
}
