package dev.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
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
        val failure = shouldThrow<IllegalArgumentException> {
            Api(endpoints = listOf(bind(getUser), bind(getUser)))
        }
        withClue(failure.message!!) { failure.message!!.contains("can never be reached") shouldBe true }
        withClue(failure.message!!) { failure.message!!.contains("GET /users/{userId}") shouldBe true }
    }

    @Test
    fun `the same path under different methods is fine`() {
        val api = Api(endpoints = listOf(bind(getUser), bind(deleteUser)))
        api.endpoints.size shouldBe 2
    }

    @Test
    fun `an endpoint declared but never bound is a startup failure`() {
        val failure = shouldThrow<IllegalArgumentException> {
            Api(
                endpoints = listOf(bind(getUser)),
                covers = listOf(getUser, listUsers, deleteUser),
            )
        }
        withClue(failure.message!!) { failure.message!!.contains("never bound") shouldBe true }
        withClue(failure.message!!) { failure.message!!.contains("GET /users") shouldBe true }
        withClue(failure.message!!) { failure.message!!.contains("DELETE /users/{userId}") shouldBe true }
    }

    @Test
    fun `covering every declared endpoint passes`() {
        val all = listOf(getUser, listUsers, deleteUser)
        val api = Api(endpoints = all.map(::bind), covers = all)
        api.endpoints.size shouldBe 3
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
        shouldThrow<IllegalArgumentException> {
            Api(endpoints = listOf(bind(getUser)), covers = listOf(twin))
        }
    }

    @Test
    fun `a response header declared twice is caught when the endpoint is built`() {
        val etag = responseHeader<String>("ETag")
        val alsoEtag = responseHeader<String>("etag")

        val failure = shouldThrow<IllegalStateException> {
            endpoint {
                get("things")
                emits(etag, alsoEtag)
                text()
            }
        }
        withClue(failure.message!!) { failure.message!!.contains("more than once") shouldBe true }
    }
}
