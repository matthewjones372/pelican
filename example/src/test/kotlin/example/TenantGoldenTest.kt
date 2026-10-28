package example

import io.github.matthewjones372.pelican.TenantResolver
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.tenantFrom
import io.github.matthewjones372.pelican.test.golden.Golden
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/** What a tenant publishes: the header a caller sends, or the host it sends to. Strict: every line. */
class TenantGoldenTest {

    private val byHeader = endpoint {
        get("orders")
        operationId = "listOrders"
        tenanted(tenantFrom(TenantResolver.header("X-Tenant")))
        text()
    }

    private val bySubdomain = endpoint {
        get("home")
        operationId = "home"
        tenanted(tenantFrom(TenantResolver.subdomain(of = "example.com")))
        text()
    }

    private val golden = Golden(directory = Paths.get("src", "test", "resources", "golden", "tenants"), strict = true)

    @Test
    fun `a tenant publishes the header to send or the host to send to, and the 400 and 404`() {
        golden.document(apiSpec(listOf(byHeader, bySubdomain), schemas = JacksonCodecs) { title = "Shops" })
    }
}
