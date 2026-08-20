package dev.pelican

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture

/**
 * The mistakes a list of endpoints can make that the type system cannot see,
 * caught when the [Api] is built rather than on the request that trips over
 * them.
 *
 * Both were previously silent: an endpoint bound twice meant the second handler
 * was simply unreachable, and one left out of the list meant a description that
 * documented itself and answered 404.
 */
class StartupChecksTest {

    private val userId = pathParam<Long>("userId")

    private val getUser = endpoint(userId) {
        get("users" / userId)
        operationId = "getUser"
        text()
    }

    private val listUsers = endpoint {
        get("users")
        operationId = "listUsers"
        text()
    }

    private val deleteUser = endpoint(userId) {
        delete("users" / userId)
        operationId = "deleteUser"
        empty()
    }

    private fun bind(ep: Endpoint<*, *>) =
        ServerEndpoint(ep) { CompletableFuture.completedStage("ok" as Any?) }

    @Test
    fun `two handlers on one route is a startup failure`() {
        val failure = assertThrows<IllegalArgumentException> {
            Api(endpoints = listOf(bind(getUser), bind(getUser)))
        }
        assertTrue(failure.message!!.contains("can never be reached"), failure.message!!)
        assertTrue(failure.message!!.contains("GET /users/{userId}"), failure.message!!)
    }

    @Test
    fun `the same path under different methods is fine`() {
        val api = Api(endpoints = listOf(bind(getUser), bind(deleteUser)))
        assertEquals(2, api.endpoints.size)
    }

    @Test
    fun `an endpoint declared but never bound is a startup failure`() {
        val failure = assertThrows<IllegalArgumentException> {
            Api(
                endpoints = listOf(bind(getUser)),
                covers = listOf(getUser, listUsers, deleteUser),
            )
        }
        assertTrue(failure.message!!.contains("never bound"), failure.message!!)
        assertTrue(failure.message!!.contains("GET /users"), failure.message!!)
        assertTrue(failure.message!!.contains("DELETE /users/{userId}"), failure.message!!)
    }

    @Test
    fun `covering every declared endpoint passes`() {
        val all = listOf(getUser, listUsers, deleteUser)
        val api = Api(endpoints = all.map(::bind), covers = all)
        assertEquals(3, api.endpoints.size)
    }

    @Test
    fun `covers is identity, not equality`() {
        // Two descriptions can be structurally identical and still be different
        // endpoints — a value-equality check here would let a genuinely unbound
        // one pass because its twin happened to be in the list.
        val twin = endpoint(userId) {
            get("users" / userId)
            operationId = "getUser"
            text()
        }
        assertThrows<IllegalArgumentException> {
            Api(endpoints = listOf(bind(getUser)), covers = listOf(twin))
        }
    }

    @Test
    fun `a response header declared twice is caught when the endpoint is built`() {
        val etag = responseHeader<String>("ETag")
        val alsoEtag = responseHeader<String>("etag")

        val failure = assertThrows<IllegalStateException> {
            endpoint {
                get("things")
                emits(etag, alsoEtag)
                text()
            }
        }
        assertTrue(failure.message!!.contains("more than once"), failure.message!!)
    }
}
