package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.failureNamedBy
import io.github.matthewjones372.pelican.spi.renderError
import io.github.matthewjones372.pelican.spi.successNamedBy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
/**
 * `orFail` widens `E` to the common supertype of the failures it is given, and
 * `Outcome` is covariant in it — so a handler may name a failure that belongs to
 * a different endpoint of the same hierarchy, and nothing says so until the
 * response is being written. What that costs the caller is the subject here.
 */
class UndeclaredResponseTest {

    private sealed interface Fault
    private data class Missing(val id: Long) : Fault
    private data class Conflicted(val why: String) : Fault

    private val missing = errorJson<Missing>(404, "No such thing")
    private val conflicted = errorJson<Conflicted>(409, "Version conflict")
    private val elsewhere = errorJson<Conflicted>(410, "Declared by another endpoint")

    private val out = json<String>(200).orFail(missing, conflicted)

    @Test
    fun `a failure the output declared comes back as itself`() {
        out.failureNamedBy(conflicted(Conflicted("x")) as Outcome.Err<*>) shouldBe conflicted
    }

    @Test
    fun `one it never declared names what it did declare instead`() {
        val refused = shouldThrow<UndeclaredResponse> {
            out.failureNamedBy(elsewhere(Conflicted("x")) as Outcome.Err<*>)
        }
        val message = refused.message
        message.shouldNotBeNull()
        message shouldContain "error:410"
        message shouldContain "error:404"
        message shouldContain "error:409"
    }

    @Test
    fun `a success it never declared is refused the same way`() {
        shouldThrow<UndeclaredResponse> {
            out.successNamedBy(json<String>(203)("x") as Outcome.Ok<*>)
        }.message shouldContain "json:203"
    }

    @Test
    fun `a success carrying the wrong payload is refused rather than encoded`() {
        // `or` widens T to what the two responses have in common, so naming the
        // first success with the second's payload compiles — and the codec
        // resolved for it is the one that cannot write it.
        val either = json<String>(200) or json<Long>(201)

        val refused = shouldThrow<UndeclaredResponse> { either.successNamedBy(ok(1L) as Outcome.Ok<*>) }
        val message = refused.message
        message.shouldNotBeNull()
        message shouldContain "json:200"
        message shouldContain "kotlin.String"
        message shouldContain "kotlin.Long"
    }

    @Test
    fun `the caller gets a reference and the log gets the reason`() {
        val rendered = renderError(UndeclaredResponse("error:410 was returned but this declares error:404"), api = null)

        rendered.error.status shouldBe 500
        rendered.error.error shouldBe "Internal server error"
        // Naming the declared responses would tell a caller about the inside of
        // the service; the log is where that goes.
        val detail = rendered.error.detail
        detail.shouldNotBeNull()
        detail shouldContain "Reference: "
        detail shouldNotContain "error:410"
        rendered.unexpected.shouldNotBeNull()
        rendered.reference.shouldNotBeNull()
    }
}
