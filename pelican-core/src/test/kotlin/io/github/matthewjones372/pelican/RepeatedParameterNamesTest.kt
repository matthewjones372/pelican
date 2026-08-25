package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * A request carries one value under a name, and OpenAPI keys a parameter by
 * name and location. Two different declarations under one name are therefore a
 * pair nothing could tell apart, and the document would carry two entries for
 * one parameter.
 */
class RepeatedParameterNamesTest {

    @Test
    fun `two query parameters of one name are refused`() {
        shouldThrow<IllegalStateException> {
            endpoint(queryParam<String>("q"), queryParam<Int>("q")) {
                get("search")
                json<String>()
            }
        }.message shouldContain "more than one query parameter named 'q'"
    }

    @Test
    fun `two headers of one name are refused, whatever their case`() {
        shouldThrow<IllegalStateException> {
            endpoint(headerParam<String>("X-Trace"), headerParam<Int>("x-trace")) {
                get("search")
                json<String>()
            }
        }.message shouldContain "X-Trace"
    }

    @Test
    fun `two cookies of one name are refused`() {
        shouldThrow<IllegalStateException> {
            endpoint(cookieParam<String>("session"), cookieParam<Int>("session")) {
                get("search")
                json<String>()
            }
        }.message shouldContain "more than one cookie named 'session'"
    }

    @Test
    fun `the same parameter declared twice is not a repetition`() {
        val q = queryParam<String>("q")
        shouldNotThrowAny {
            endpoint(q) {
                get("search")
                query(q)
                json<String>()
            }
        }
    }

    @Test
    fun `one name in two locations is fine, because a request keeps them apart`() {
        shouldNotThrowAny {
            endpoint(queryParam<String>("token"), headerParam<String>("token")) {
                get("search")
                json<String>()
            }
        }
    }

    @Test
    fun `cookies keep their case, because a cookie name is case-sensitive`() {
        shouldNotThrowAny {
            endpoint(cookieParam<String>("Session"), cookieParam<String>("session")) {
                get("search")
                json<String>()
            }
        }
    }
}
