package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.withoutDotSegments
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

class RequestPathsTest {

    @Test
    fun `a path with no dot segment comes back as the same string`() {
        val path = "/users/a.b/..c/%2Ex/..."
        withoutDotSegments(path) shouldBeSameInstanceAs path
    }

    @Test
    fun `a single dot is dropped and a double dot drops the segment before it`() {
        withoutDotSegments("/a/./b/../c") shouldBe "/a/c"
    }

    @Test
    fun `a dot segment last leaves a trailing slash`() {
        withoutDotSegments("/a/b/..") shouldBe "/a/"
        withoutDotSegments("/a/.") shouldBe "/a/"
    }

    @Test
    fun `an encoded dot is a dot, in either case`() {
        withoutDotSegments("/a/%2e%2E/b") shouldBe "/b"
        withoutDotSegments("/a/.%2e/b") shouldBe "/b"
    }

    @Test
    fun `climbing above the root is null`() {
        withoutDotSegments("/../a").shouldBeNull()
        withoutDotSegments("/a/%2E%2E/..").shouldBeNull()
    }

    @Test
    fun `other escapes are left for the index`() {
        withoutDotSegments("/a/./b%2Fc") shouldBe "/a/b%2Fc"
    }
}
