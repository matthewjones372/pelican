package example.backends

import io.github.matthewjones372.pelican.Endpoint
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.jvm.functions.Function2
import kotlin.jvm.functions.Function3

/**
 * Which binders exist on which backend, written down.
 *
 * `handledNow` is the most-typed identifier in a Pelican service and it means
 * the same thing on all three — handled in place, on the request — while *in
 * place* is a thread on Pekko and http4k and a coroutine on Ktor. That is a
 * documented difference rather than an accident (see `docs/reference.md`), and
 * the two claims underneath it are the ones below: every backend spells the
 * in-place binders the same way, and only the two whose handlers run on a
 * thread carry the `…By` family that takes a `CompletionStage`.
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

    @Test
    fun `every backend binds in place under the same names`() {
        listOf(PEKKO, HTTP4K, KTOR).forEach { pkg ->
            withClue(pkg) { bindersOf(pkg) intersect inPlace shouldBe inPlace }
        }
    }

    @Test
    fun `and the CompletionStage family is on the two that answer on a thread`() {
        bindersOf(PEKKO) shouldBe inPlace + staged
        bindersOf(HTTP4K) shouldBe inPlace + staged

        // Nothing to reach for on Ktor: a handler there may already await
        // anything, which is what `handledBy` buys on the other two.
        bindersOf(KTOR) shouldBe inPlace
    }

    @Test
    fun `handledNow is one name, and on ktor its lambda carries a continuation`() {
        // A `suspend Params.(I) -> T` erases to one parameter more than a
        // `Params.(I) -> T` does, which is what "in place is a coroutine here"
        // looks like from outside.
        lambdaTakenBy(PEKKO, "handledNow") shouldBe Function2::class.java
        lambdaTakenBy(HTTP4K, "handledNow") shouldBe Function2::class.java
        lambdaTakenBy(KTOR, "handledNow") shouldBe Function3::class.java
    }

    private companion object {
        const val PEKKO = "io.github.matthewjones372.pelican.pekko"
        const val HTTP4K = "io.github.matthewjones372.pelican.http4k"
        const val KTOR = "io.github.matthewjones372.pelican.ktor"
    }
}
