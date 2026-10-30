package io.github.matthewjones372.pelican

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.time.Duration

class ApiBinaryCompatibilityTest {
    private val configured = configuredApi()
    private val defaults = Api(
        configured.endpoints, NoCodecs, "API", "1.0.0", null, emptyList(), emptyList(), null,
        10_000, 8L * 1024 * 1024, null, emptyList(), false, null, emptyList(), emptyList(),
        ApiErrorEnvelope, null, null, emptyMap(), null, true, null, null, null,
    )

    @ParameterizedTest
    @ValueSource(strings = ["released-1.2.0", "main-b9632bd"])
    fun `frozen full call sites preserve every supplied setting`(baseline: String) {
        frozen(baseline, "full").snapshot() shouldBe
            configured.snapshot() + defaults.snapshot().filterKeys(::newSetting)
    }

    @ParameterizedTest
    @ValueSource(strings = ["released-1.2.0", "main-b9632bd"])
    fun `frozen named and default mask call sites retain the original defaults`(baseline: String) {
        frozen(baseline, "defaults").snapshot() shouldBe defaults.snapshot()
        frozen(baseline, "named").snapshot() shouldBe defaults.snapshot() + mapOf(
            "getTitle" to configured.title, "getMaxBodyBytes" to configured.maxBodyBytes,
            "getMaxFrameBytes" to configured.maxBodyBytes, "getIdempotency" to configured.idempotency.normalized(),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["released-1.2.0", "main-b9632bd"])
    fun `frozen factory DSL and builder entry points preserve settings and defaults`(baseline: String) {
        listOf("factory", "builder").forEach { method ->
            frozen(baseline, method).snapshot() shouldBe
                configured.snapshot() + defaults.snapshot().filterKeys(::newSetting)
        }
        frozen(baseline, "factoryDefaults").snapshot() shouldBe defaults.snapshot()
    }

    @Test
    fun `the original full twenty five parameter call site preserves newer settings too`() {
        frozen("main-b9632bd", "full", "FrozenCurrentConsumer").snapshot() shouldBe configured.snapshot()
    }

    private fun frozen(baseline: String, method: String, type: String = "FrozenConsumer"): Api {
        val jar = checkNotNull(javaClass.getResource("/compatibility/$baseline.jar"))
        return URLClassLoader(arrayOf(jar), Api::class.java.classLoader).use { loader ->
            val consumer = loader.loadClass("compatibility.$type")
            try {
                val instance = consumer.getConstructor().newInstance()
                consumer.getMethod(method, Api::class.java).invoke(instance, configured) as Api
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
    }
}

private fun newSetting(getter: String): Boolean =
    getter in setOf("getCsrf", "getTenants", "getRateLimits", "getResponseHeaders")

private fun Api.snapshot(): Map<String, Any?> = Api::class.java.declaredMethods
    .filter { it.name.startsWith("get") && it.parameterCount == 0 }
    .associate { it.name to it.invoke(this).normalized() }

private fun Any?.normalized(): Any? = if (this is Idempotency) listOf(store, ttl, lease) else this

private fun configuredApi(): Api {
    val description = endpoint { get("compatibility"); text() }
    val binding = ServerEndpoint(description) { error("the compatibility fixture does not dispatch requests") }
    val scheme = bearerAuth()
    return Api(
        listOf(binding), object : Codecs by NoCodecs {}, "Configured", "7", "Synthetic settings",
        listOf("https://compatibility.example"), listOf(scheme.requires()), cors("https://client.example"),
        321, 7654, 4567, listOf(before {}), true, { _, _, _ -> }, listOf(description),
        listOf(webhook("compatibilityEvent") { empty() }), ProblemDetails, RefusalObserver { _, _, _ -> },
        pages("compatibility-pages"), mapOf(scheme to Authenticator { null }),
        Idempotency(InMemoryIdempotencyStore(), Duration.ofHours(2), Duration.ofSeconds(13)), false,
        Tenants(tenantFrom(TenantResolver.header("X-Tenant")), TenantDirectory { true }, "business"),
        RateLimitStore { _, _, _ -> Taken.Allowed(17) }, ResponseHeaderPolicy { add("X-Compatibility", "yes") },
    )
}
