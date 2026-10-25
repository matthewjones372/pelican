package example.backends

import io.github.matthewjones372.pelican.uring.RequiresUring
import io.github.matthewjones372.pelican.uring.UringTests
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * [allBackends] less io_uring where its tests skip on this host, so a suite whose companion starts every backend
 * still runs on Pekko and Jetty there. [UringCasesTest] reports the io_uring cases left out as a skip.
 */
val runnableBackends: List<Backend> = allBackends.filter { it !== OnUring || UringTests.run }

/** Where the suites over [runnableBackends] leave io_uring out, this is skipped with the reason. */
@RequiresUring
class UringCasesTest {
    @Test
    fun `the io_uring cases of the shared suites run on this host`() {
        OnUring.start(port = 0).use { it.baseUrl shouldStartWith "http://" }
    }
}
