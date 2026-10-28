package example

import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.rateLimit
import io.github.matthewjones372.pelican.test.golden.Golden
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.time.Duration.Companion.minutes

/** What a rate limit publishes: the 429, its Retry-After, and the limit in words. Strict: every line. */
class RateLimitGoldenTest {

    private val caller = authenticated(bearerAuth(), Identity::subject)

    private val listOrders = endpoint {
        get("orders")
        operationId = "listOrders"
        authenticatedBy(caller)
        rateLimited(rateLimit("orders", 100, per = 1.minutes, burst = 20))
        text()
    }

    private val golden =
        Golden(directory = Paths.get("src", "test", "resources", "golden", "rate-limits"), strict = true)

    @Test
    fun `a limit publishes its 429, the wait it names, and the limit itself`() {
        golden.document(apiSpec(listOf(listOrders), schemas = JacksonCodecs) { title = "Orders" })
    }
}
