package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.RequestView
import io.github.matthewjones372.pelican.spi.Slots
import io.github.matthewjones372.pelican.spi.plan
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.random.Random

class EndpointPlanTest {

    private val q = queryParam<String>("q").optional()
    private val tags = queryParam<String>("tag").repeated().optional()
    private val n = queryParam<Long>("n")
    private val elsewhere = queryParam<String>("elsewhere")

    private val search = endpoint(q, tags) { get("search"); text() }
    private val counted = endpoint(n) { get("count"); text() }

    private fun view(query: String?) = object : RequestView {
        override val method = "GET"
        override val rawPath = "/"
        override val rawQuery = query

        override fun header(name: String): String? = null

        override fun headers(name: String): List<String> = emptyList()
    }

    private fun decoded(ep: Endpoint<*, *>, query: String?): Slots =
        ep.plan().let { plan -> plan.slots().also { plan.decodePlainInputs(view(query), it) } }

    /** What every pair decoded as a form, then grouped, made of [query]: the reading the one pass replaces. */
    private fun asAForm(query: String): Result<Map<String, List<String>>> = try {
        Result.success(parseFormBody(query).groupBy({ it.first }, { it.second }))
    } catch (e: IllegalArgumentException) {
        Result.failure(e)
    }

    @Test
    fun `one pass reads every query exactly as decoding each pair as a form does`() {
        val alphabet = "qtag=&%+-0f9zG.é٣".toCharArray()
        val random = Random(89)
        repeat(20_000) {
            val query = String(CharArray(random.nextInt(14)) { alphabet[random.nextInt(alphabet.size)] })
            val expected = asAForm(query)
            withClue("?$query") {
                val failure = expected.exceptionOrNull()
                if (failure != null) {
                    val refused = shouldThrow<ApiException> { decoded(search, query) }
                    refused.status shouldBe 400
                    refused.message shouldBe "Illegal query string"
                    refused.detail shouldBe failure.message
                } else {
                    val form = expected.getOrThrow()
                    val values = decoded(search, query)
                    values[q] shouldBe form["q"]?.first()
                    values[tags] shouldBe form["tag"]?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
                }
            }
        }
    }

    @Test
    fun `an illegal escape under a name nobody declared is still the 400`() {
        val refused = shouldThrow<ApiException> { decoded(counted, "n=1&other=%zz") }
        refused.status shouldBe 400
        refused.detail shouldBe shouldThrow<IllegalArgumentException> { parseFormBody("%zz") }.message
    }

    @Test
    fun `percent-escapes that are not UTF-8 are a 400 with one fixed detail, wherever they are`() {
        // Truncated, a stray continuation byte, an overlong `/`, a surrogate, past U+10FFFF, and a split sequence.
        val bytes = listOf("%e9", "%c3", "%80", "%c0%af", "%ed%a0%80", "%f4%90%80%80", "%c3a%a9")
        val queries = bytes.flatMap { listOf("q=$it", "other=$it", "$it=x", it, "q=ok&tag=$it") }
        queries.forEach { query ->
            withClue("?$query") {
                val refused = shouldThrow<ApiException> { decoded(search, query) }
                refused.status shouldBe 400
                refused.message shouldBe "Illegal query string"
                refused.detail shouldBe "The percent-encoded bytes are not UTF-8"
            }
        }
    }

    @Test
    fun `UTF-8 written as escapes reads as the text it spells`() {
        decoded(search, "q=%C3%A9%E2%82%AC%F0%9F%98%80")[q] shouldBe "é€😀"
    }

    @Test
    fun `an illegal escape is still URLDecoder's 400, even beside bytes that are not UTF-8`() {
        shouldThrow<ApiException> { decoded(search, "q=%e9a%zz") }.detail shouldBe
            shouldThrow<IllegalArgumentException> { parseFormBody("%zz") }.message
    }

    @Test
    fun `an endpoint declaring no query never reads it`() {
        decoded(endpoint { get("none"); text() }, "x=%zz").size shouldBe 0
    }

    @Test
    fun `the first value of a single parameter wins, and its codec decodes it`() {
        decoded(counted, "n=%34%32&n=7")[n] shouldBe 42L
    }

    @Test
    fun `a missing required parameter is the same 400`() {
        shouldThrow<ApiException> { decoded(counted, "m=1") }.message shouldBe "Missing required query parameter 'n'"
    }

    @Test
    fun `params read through slots still throw for a key the endpoint never declared`() {
        val params = Params(decoded(counted, "n=1"), underlying = null, endpoint = counted)
        params[n] shouldBe 1L
        shouldThrow<IllegalStateException> { params[elsewhere] }.message shouldContain "never declared"
        shouldThrow<IllegalStateException> { decoded(counted, "n=1")[elsewhere] = "x" }
    }

    @Test
    fun `the bag walks as a map, in declaration order`() {
        decoded(search, "tag=a&q=x").entries.map { it.key to it.value } shouldBe listOf(q to "x", tags to listOf("a"))
    }

    @Test
    fun `a tenanted endpoint has a slot for its tenant, and its queries still land in their own`() {
        val tenant = tenantFrom(TenantResolver.subdomain(of = "example.com"))
        val tenanted = endpoint(n) {
            get("orders")
            tenanted(tenant)
            text()
        }

        val slots = decoded(tenanted, "n=3")
        slots[tenant] = Tenant("acme")

        slots[tenant] shouldBe Tenant("acme")
        slots[n] shouldBe 3L
    }
}
