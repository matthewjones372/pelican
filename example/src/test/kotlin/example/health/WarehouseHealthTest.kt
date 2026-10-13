package example.health

import io.github.matthewjones372.pelican.health.HealthReport
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.inMemory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf

class WarehouseHealthTest {

    private val warehouse = Warehouse()
    private val health = warehouseHealth(warehouse)
    private val client = warehouseApi(health).inMemory()

    @Test
    fun `both probes pass while the warehouse answers`() {
        client.call(health.live, Unit).status shouldBe "pass"
        client.call(health.ready, Unit).checks.keys shouldBe
            setOf("threads:responseTime", "heap:responseTime", "disk:responseTime", "warehouse:responseTime")
    }

    @Test
    fun `when the warehouse goes quiet ready answers 503 and live still passes`() {
        warehouse.goQuiet()

        client.call(health.live, Unit).status shouldBe "pass"

        // A 503 is not a success, so the typed client's `call` would throw: read the response instead.
        val ready = client.response(health.ready, Unit)
        ready.status shouldBe 503
        ready.contentType.orEmpty() shouldStartWith "application/health+json"
        val report = JacksonCodecs.codec<HealthReport>(typeOf<HealthReport>()).decodeFromString(ready.body)
        report.status shouldBe "fail"
        report.checks.getValue("warehouse:responseTime").single().status shouldBe "fail"
    }
}
