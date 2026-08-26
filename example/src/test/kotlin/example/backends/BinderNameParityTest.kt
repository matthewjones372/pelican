package example.backends

import io.github.matthewjones372.pelican.Endpoint
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.jvm.functions.Function2

/**
 * Which binders exist on which backend, written down.
 *
 * `handledNow` is the most-typed identifier in a Pelican service and it has to
 * mean the same thing wherever it is spelled — handled in place, on the request
 * — while *in place* is a thread on a backend whose handlers run on one and a
 * coroutine on a backend whose handlers suspend. That is a documented
 * difference rather than an accident (see `docs/reference.md`), and the two
 * claims underneath it are the ones below: every backend spells the in-place
 * binders the same way, and one whose handlers run on a thread also carries the
 * `…By` family that takes a `CompletionStage`.
 *
 * Asked of [backends], so a returning module is held to the naming by a row.
 */
class BinderNameParityTest {

    /** Binding a description to a handler: everything taking an [Endpoint] first. */
    private fun bindersOf(pkg: String): Set<String> =
        Class.forName("$pkg.HandlersKt").methods
            .filter { it.parameterTypes.firstOrNull() == Endpoint::class.java }
            .map { it.name }
            .toSet()

    private fun lambdaTakenBy(pkg: String, binder: String): Class<*> =
        Class.forName("$pkg.HandlersKt").methods.single { it.name == binder }.parameterTypes[1]

    private val inPlace = setOf(
        "handledNow", "handledWith", "handledOrFail", "handledOneOf",
        "streamedNow", "streamedOrFail", "bytesNow", "bytesOrFail",
    )

    private val staged = setOf(
        "handledBy", "handledByOrFail", "handledByOneOf", "streamedBy", "streamedByOrFail",
    )

    private val backends = listOf(PEKKO)

    @Test
    fun `every backend binds in place under the same names`() {
        backends.forEach { pkg ->
            withClue(pkg) { bindersOf(pkg) intersect inPlace shouldBe inPlace }
        }
    }

    @Test
    fun `and the CompletionStage family is on the one that answers on a thread`() {
        bindersOf(PEKKO) shouldBe inPlace + staged
    }

    @Test
    fun `handledNow takes a plain lambda where a handler runs on a thread`() {
        // A `suspend Params.(I) -> T` erases to one parameter more than a
        // `Params.(I) -> T` does, which is what "in place is a coroutine here"
        // would look like from outside.
        lambdaTakenBy(PEKKO, "handledNow") shouldBe Function2::class.java
    }

    private companion object {
        const val PEKKO = "io.github.matthewjones372.pelican.pekko"
    }
}
