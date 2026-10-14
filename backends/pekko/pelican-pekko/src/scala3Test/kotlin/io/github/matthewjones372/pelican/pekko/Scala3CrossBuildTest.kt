package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.pathParam
import io.kotest.matchers.shouldBe
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.stream.javadsl.Sink
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test

/**
 * This module compiled against Pekko's Scala 2.13 cross-build, running against
 * its Scala 3 one — which is the whole of what a consumer on Scala 3 does after
 * spec 0037 stopped this module shipping either.
 *
 * A JSON body through the codec seam and a path parameter, because between
 * them they reach `javadsl`, `ByteString` and `ClassicActorSystemProvider`:
 * every Pekko name this module knows. A class that moved between the
 * cross-builds fails here as a `NoClassDefFoundError`, which is exactly the
 * failure this source set exists to have happen in the build rather than in
 * somebody's service.
 */
@Suppress("ForbiddenVoid") // `Behaviors.empty<Void>()` is Pekko's Java DSL; see config/detekt/detekt.yml.
class Scala3CrossBuildTest {

    data class Order(val id: Long, val item: String)

    private val orderId = pathParam<Long>("orderId")

    private val getOrder = endpoint(orderId) {
        get("orders" / orderId)
        json<Order>()
    }

    private val route = api(
        endpoints = listOf(getOrder handledNow { id -> Order(id, "widget") }),
        codecs = JacksonCodecs,
    ).toRoute(system).function(system)

    @Test
    fun `the interpreter answers on the Scala 3 cross-build of Pekko`() {
        val response = route.apply(HttpRequest.GET("/orders/7")).toCompletableFuture().join()

        response.status().intValue() shouldBe 200

        val body = response.entity().dataBytes
            .runFold(org.apache.pekko.util.ByteString.emptyByteString(), { a, b -> a.concat(b) }, system)
            .toCompletableFuture().join()

        body.utf8String() shouldBe """{"id":7,"item":"widget"}"""
    }

    @Test
    fun `a stream reaches the socket layer on the Scala 3 cross-build too`() {
        val sunk = org.apache.pekko.stream.javadsl.Source.range(1, 3)
            .runWith(Sink.seq(), system)
            .toCompletableFuture().join()

        sunk shouldBe listOf(1, 2, 3)
    }

    private companion object {
        val system: ActorSystem<Void> = ActorSystem.create(Behaviors.empty(), "pelican-scala3")

        @JvmStatic
        @AfterAll
        fun stop() {
            system.terminate()
            system.whenTerminated.toCompletableFuture().join()
        }
    }
}
