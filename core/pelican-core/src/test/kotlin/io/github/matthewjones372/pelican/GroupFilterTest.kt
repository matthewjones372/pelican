package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.handlerFor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * Filters registered on the `Api` run for every endpoint in it. These are the
 * ones attached to a group, for the case where the grouping is structural and
 * the description does not carry it — `onlyWhen` is the answer when it does.
 */
class GroupFilterTest {

    private val health = endpoint {
        get("health")
        operationId = "health"
        text()
    }

    private val report = endpoint {
        get("reports")
        operationId = "reports"
        text()
    }

    private fun bind(e: Endpoint<*, *>) =
        ServerEndpoint(e) { CompletableFuture.completedStage("handled" as Any?) }

    private fun mark(log: MutableList<String>, name: String) = Filter { p, next ->
        log += name
        next(p)
    }

    /** Every endpoint in the API, once each, in the order they were listed. */
    private fun Api.runAll(): Unit = endpoints.forEach { se ->
        handlerFor(se)(Params(emptyMap(), null, se.endpoint)).toCompletableFuture().join()
    }

    @Test
    fun `a group's filter runs for its own endpoints and no others`() {
        val log = mutableListOf<String>()
        api(
            endpoints = listOf(bind(health)) + listOf(bind(report)).filteredBy(mark(log, "group")),
        ).runAll()

        log shouldBe listOf("group")
    }

    @Test
    fun `an api-wide filter still runs for an endpoint carrying its own`() {
        val log = mutableListOf<String>()
        api(
            endpoints = listOf(bind(report)).filteredBy(mark(log, "group")),
        ) {
            filter(mark(log, "api"))
        }.runAll()

        log shouldBe listOf("api", "group")
    }

    @Test
    fun `a group's filters run in the order they were attached`() {
        val log = mutableListOf<String>()
        api(
            endpoints = listOf(bind(report)).filteredBy(mark(log, "first"), mark(log, "second")),
        ).runAll()

        log shouldBe listOf("first", "second")
    }

    @Test
    fun `attaching nothing hands back the list it was given`() {
        val bound = listOf(bind(health))

        bound.filteredBy() shouldBeSameInstanceAs bound
    }
}
