package example

import dev.surefold.pelican.TenantResolver
import dev.surefold.pelican.apiSpec
import dev.surefold.pelican.endpoint
import dev.surefold.pelican.jackson.JacksonCodecs
import dev.surefold.pelican.tenantFrom
import dev.surefold.pelican.test.golden.Golden
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
