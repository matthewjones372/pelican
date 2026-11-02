package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class QueryMethodTest {

    data class Filter(val status: String)

    @Test
    fun `query declares a QUERY operation on the path`() {
        val search = endpoint(jsonBody<Filter>()) {
            query("orders" / "search")
            json<List<String>>()
        }

        search.method shouldBe Method.QUERY
        search.pathSpec.template shouldBe "/orders/search"
    }

    @Test
    fun `a path given as a string works too`() {
        endpoint(jsonBody<Filter>()) {
            query("search")
            json<List<String>>()
        }.method shouldBe Method.QUERY
    }

    @Test
    fun `QUERY comes after the methods that already existed`() {
        Method.entries.map { it.name } shouldBe
            listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "QUERY")
    }

    @Test
    fun `QUERY is idempotent, so a retry may repeat it`() {
        IDEMPOTENT_METHODS shouldContain Method.QUERY
    }

    @Test
    fun `POST and PATCH stay out of the idempotent methods`() {
        IDEMPOTENT_METHODS shouldNotContain Method.POST
        IDEMPOTENT_METHODS shouldNotContain Method.PATCH
    }

    @Test
    fun `an idempotency key on a QUERY is refused, as on any idempotent method`() {
        shouldThrow<IllegalStateException> {
            endpoint(jsonBody<Filter>()) {
                query("search")
                idempotencyKey()
                json<List<String>>()
            }
        }.message shouldContain "idempotent already"
    }

    @Test
    fun `a QUERY without a body is refused when it is declared`() {
        shouldThrow<IllegalStateException> {
            endpoint {
                query("search")
                json<List<String>>()
            }
        }.message shouldContain "QUERY"
    }

    @Test
    fun `a QUERY whose body is raw or multipart is refused, since no codec checks its Content-Type`() {
        shouldThrow<IllegalStateException> {
            endpoint(rawBody()) {
                query("search")
                json<List<String>>()
            }
        }.message shouldContain "QUERY"
    }

    @Test
    fun `a QUERY may also take query-string parameters`() {
        val page = queryParam<Int>("page")
        endpoint(jsonBody<Filter>(), page) {
            query("search")
            json<List<String>>()
        }.queries shouldBe listOf(page)
    }

    @Test
    fun `contentLocation is a Content-Location response header`() {
        contentLocation.name shouldBe "Content-Location"
    }
}
