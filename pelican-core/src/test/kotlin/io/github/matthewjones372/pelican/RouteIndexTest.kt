package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * Matching without trying the others.
 *
 * Every claim here is one an ordered scan already made — the point of the trie
 * is that it makes the same ones without looking at every endpoint, so the
 * suite is about agreement rather than about speed.
 */
class RouteIndexTest {

    private val orderId = pathParam<Long>("orderId")
    private val userId = pathParam<Long>("userId")
    private val name = pathParam<String>("name")

    private fun bound(ep: Endpoint<*, *>) =
        ServerEndpoint(ep) { CompletableFuture.completedFuture(null) }

    private val listOrders = bound(endpoint { get("orders"); text() })
    private val watchOrders = bound(endpoint { get("orders" / "watch"); text() })
    private val getOrder = bound(endpoint(orderId) { get("orders" / orderId); text() })
    private val deleteOrder = bound(endpoint(orderId) { delete("orders" / orderId); text() })
    private val userPosts = bound(endpoint(name) { get("users" / name / "posts"); text() })
    private val getUser = bound(endpoint(userId) { get("users" / userId); text() })
    private val adminUser = bound(endpoint { get("users" / "admin"); text() })
    private val root = bound(endpoint { get(""); text() })

    private val index = listOf(
        listOrders, watchOrders, getOrder, deleteOrder, userPosts, getUser, adminUser, root,
    ).routeIndex()

    private fun match(method: Method, path: String): Pair<ServerEndpoint?, Map<ParamKey<*>, Any?>> {
        val values = LinkedHashMap<ParamKey<*>, Any?>()
        return index.match(method, path, values) to values
    }

    @Test
    fun `a literal path finds its endpoint`() {
        match(Method.GET, "/orders").first shouldBe listOrders
    }

    @Test
    fun `a capture finds its endpoint and yields the segment, decoded into the declared type`() {
        val (found, values) = match(Method.GET, "/orders/7")
        found shouldBe getOrder
        values[orderId] shouldBe 7L
    }

    @Test
    fun `a literal beats a capture at the same position`() {
        match(Method.GET, "/orders/watch").first shouldBe watchOrders
    }

    @Test
    fun `and the capture is still reached for anything else`() {
        match(Method.GET, "/orders/9").first shouldBe getOrder
    }

    @Test
    fun `two methods on one path are told apart`() {
        match(Method.DELETE, "/orders/7").first shouldBe deleteOrder
    }

    @Test
    fun `a method nobody declared on a path that exists is no match, not a wrong one`() {
        match(Method.PUT, "/orders/7").first.shouldBeNull()
    }

    @Test
    fun `a path nobody described is no match`() {
        match(Method.GET, "/invoices/1").first.shouldBeNull()
    }

    @Test
    fun `a path longer than anything described is no match`() {
        match(Method.GET, "/orders/7/lines/2").first.shouldBeNull()
    }

    @Test
    fun `two endpoints may name one captured position differently`() {
        // `/users/{userId}` and `/users/{name}/posts` capture the same segment.
        // Each handler reads the key it declared, so the name comes from the
        // endpoint that matched rather than from the node that captured.
        val (shallow, shallowValues) = match(Method.GET, "/users/12")
        shallow shouldBe getUser
        shallowValues[userId] shouldBe 12L

        val (deep, deepValues) = match(Method.GET, "/users/ada/posts")
        deep shouldBe userPosts
        deepValues[name] shouldBe "ada"
    }

    @Test
    fun `a failed descent falls through to the capture rather than giving up`() {
        // `admin` is a literal here, so the walk takes that branch first and
        // finds nothing under it for `/posts`. Giving up there would lose a
        // path that is plainly described; it has to come back for the capture.
        match(Method.GET, "/users/admin").first shouldBe adminUser

        val (found, values) = match(Method.GET, "/users/admin/posts")
        found shouldBe userPosts
        values[name] shouldBe "admin"
    }

    @Test
    fun `a segment that matches the shape and not the type is the codec's refusal`() {
        // The scan behaved this way too: the route is found by shape and the
        // declared codec is what rejects the value, so this is a 400 naming the
        // parameter rather than a 404 naming nothing.
        shouldThrow<DecodeFailure> { match(Method.GET, "/users/ada") }
            .message!! shouldContain "userId"
    }

    @Test
    fun `a percent-encoded segment is decoded before its codec sees it`() {
        match(Method.GET, "/users/ada%20lovelace/posts").second[name] shouldBe "ada lovelace"
    }

    @Test
    fun `a plus in a segment is a plus`() {
        // A path is not a form. `URLDecoder` read this as two spaces, so the
        // one language whose name is a pun was unroutable.
        match(Method.GET, "/users/c++/posts").second[name] shouldBe "c++"
    }

    @Test
    fun `and an encoded plus means the same thing`() {
        match(Method.GET, "/users/c%2B%2B/posts").second[name] shouldBe "c++"
    }

    @Test
    fun `an encoded slash stays inside the segment that carried it`() {
        val (found, values) = match(Method.GET, "/users/a%2Fb/posts")
        found shouldBe userPosts
        values[name] shouldBe "a/b"
    }

    @Test
    fun `a literal is matched decoded, so an escape spelling it still reaches it`() {
        match(Method.GET, "/users/%61dmin").first shouldBe adminUser
    }

    @Test
    fun `a malformed escape is a 400 rather than a 404 or a 500`() {
        shouldThrow<ApiException> { match(Method.GET, "/users/%zz") }.status shouldBe 400
    }

    @Test
    fun `the root path is describable and reachable`() {
        match(Method.GET, "/").first shouldBe root
    }

    @Test
    fun `a trailing slash does not change which endpoint answers`() {
        match(Method.GET, "/orders/").first shouldBe listOrders
    }

    @Test
    fun `nothing is written into the bag for a path that does not match`() {
        val (found, values) = match(Method.GET, "/orders/7/nope")
        found.shouldBeNull()
        values.shouldBeEmptyMap()
    }

    private fun Map<ParamKey<*>, Any?>.shouldBeEmptyMap() = this.size shouldBe 0
}
