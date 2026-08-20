package dev.pelican

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The bookkeeping behind `orFail`: a failure listed on an output is part of
 * the endpoint's type *and* part of its document, whether it was declared
 * inside the block or as a value shared between endpoints.
 *
 * What cannot be tested here is the part that matters most — that a handler
 * returning an undeclared failure does not compile. That is a property of the
 * binders in `pelican-pekko`, and the evidence for it is that the example
 * compiles at all.
 */
class DeclaredFailuresTest {

    data class Problem(val code: String)
    data class Widget(val id: Long)

    private val widgetId = pathParam<Long>("widgetId")

    private val missing = errorJson<Problem>(404, "No widget with that id")
    private val forbidden = errorJson<Problem>(403, "Not yours")

    @Test
    fun `a failure declared as a value is documented once`() {
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            json<Widget>() orFail missing
        }

        assertEquals(1, ep.errors.size)
        assertEquals(404, ep.errors.single().status)
        assertEquals("No widget with that id", ep.errors.single().description)
        assertEquals(typeOfProblem(), ep.errors.single().type)
    }

    @Test
    fun `a failure declared inside the block is documented once, not twice`() {
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            json<Widget>() orFail errorJson<Problem>(404, "No widget with that id")
        }

        assertEquals(1, ep.errors.size)
    }

    @Test
    fun `several failures keep their own statuses`() {
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            json<Widget>().orFail(missing, forbidden)
        }

        assertEquals(listOf(404, 403), ep.errors.map { it.status })
    }

    @Test
    fun `a failure documented but not declared stays documentation only`() {
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            errorJson<Problem>(404, "No widget with that id")
            json<Widget>()
        }

        // Documented, so the spec is unchanged from before `orFail` existed —
        // but the output is a plain JsonOutput, so the handler is the total
        // one and the 404 is still whatever the handler throws.
        assertEquals(1, ep.errors.size)
        assertTrue(ep.output is JsonOutput<*>)
    }

    @Test
    fun `two failures cannot share a status on one output`() {
        val clash = assertThrows(IllegalArgumentException::class.java) {
            endpoint(widgetId) {
                get("widgets" / widgetId)
                json<Widget>().orFail(missing, errorJson<Problem>(404, "Also not found"))
            }
        }
        assertTrue(clash.message!!.contains("404"), clash.message)
    }

    @Test
    fun `orFail does not stack`() {
        assertThrows(IllegalArgumentException::class.java) {
            endpoint(widgetId) {
                get("widgets" / widgetId)
                (json<Widget>() orFail missing) orFail forbidden
            }
        }
    }

    @Test
    fun `the wrapped output still drives status, media type and payload`() {
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            json<Widget>(status = 201) orFail missing
        }

        assertEquals(201, ep.output.status)
        assertEquals("application/json", ep.output.mediaType)
        assertEquals(typeOfWidget(), ep.output.payloadType)
    }

    @Test
    fun `the failure names itself, so the same type can carry two statuses`() {
        val (declared, error) = missing(Problem("gone")).let { it as Outcome.Err }
        assertSame(missing, declared)
        assertEquals(Problem("gone"), error)
        assertEquals(403, (forbidden(Problem("gone")) as Outcome.Err).declared.status)
    }

    sealed interface Trouble {
        data class Missing(val id: Long) : Trouble
        data class Denied(val who: String) : Trouble
    }

    @Test
    fun `several payload types infer to their common supertype`() {
        val gone = errorJson<Trouble.Missing>(404, "No widget with that id")
        val denied = errorJson<Trouble.Denied>(403, "Not yours")

        // The point of this test is the declared type on the left: if the
        // inference ever stopped producing the sealed supertype, a handler
        // could no longer answer with a `when` over Trouble.
        val ep: Endpoint<Long, Fallible<Trouble, Widget>> = endpoint(widgetId) {
            get("widgets" / widgetId)
            json<Widget>().orFail(gone, denied)
        }

        assertEquals(listOf(404, 403), ep.errors.map { it.status })

        val answer: Outcome<Trouble, Widget> = gone(Trouble.Missing(7))
        assertEquals(Trouble.Missing(7), (answer as Outcome.Err).error)
    }

    private fun typeOfProblem() = kotlin.reflect.typeOf<Problem>()
    private fun typeOfWidget() = kotlin.reflect.typeOf<Widget>()
}
