package example.backends

import io.github.matthewjones372.pelican.gannet.GannetTests
import io.github.matthewjones372.pelican.gannet.RequiresGannet
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * [allBackends] less Gannet where its tests skip on this host, so a suite whose companion starts every backend
 * still runs on Pekko and Jetty there. [GannetCasesTest] reports the Gannet cases left out as a skip.
 */
val runnableBackends: List<Backend> = allBackends.filter { it !== OnGannet || GannetTests.run }

/** Where the suites over [runnableBackends] leave Gannet out, this is skipped with the reason. */
@RequiresGannet
class GannetCasesTest {
    @Test
    fun `the Gannet cases of the shared suites run on this host`() {
        OnGannet.start(port = 0).use { it.baseUrl shouldStartWith "http://" }
    }
}
