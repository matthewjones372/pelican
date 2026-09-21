package io.github.matthewjones372.pelican

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The CI matrix names three JDKs, and `jvmToolchain(21)` sets the *Java*
 * toolchain as well as the Kotlin one — so without a launcher of its own every
 * `Test` task runs on 21 whatever the job is called, and the matrix exercises
 * one runtime while claiming three. Spec 0049 was read as a JDK 25 failure for
 * exactly that reason and was JDK 21's the whole time.
 *
 * The build passes the JDK it is running on; this asserts the tests are on it.
 */
class TestRuntimeIsTheBuildRuntimeTest {

    @Test
    fun `the tests run on the JDK that launched the build, not on the compile toolchain`() {
        val launcher = withClue("the build did not say which JDK launched it") {
            System.getProperty("pelican.launcherJavaVersion").shouldNotBeNull()
        }
        val running = Runtime.version().feature()

        withClue("tests ran on $running, the build launched on $launcher") {
            running shouldBe launcher.toInt()
        }
    }
}
