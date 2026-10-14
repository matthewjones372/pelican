package io.github.matthewjones372.pelican.streams

import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.from
import io.github.matthewjones372.pelican.ErrorOutput
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.StreamIn
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.pekko.toSource
import java.util.concurrent.CompletionStage

/*
 * The seam, and only the seam.
 *
 * A streaming body arrives as a `StreamIn<T>`, which `pelican-pekko` already
 * hands over as a Pekko `Source`. What a handler usually wants next is not a
 * `Source` — it is somewhere to put the failure. A `Source` carries one as a
 * thrown exception a materialised stage reports later; lark's `Stream<E, A>`
 * carries it as a value in the type, so a row that cannot be understood is a
 * declared `E` rather than something the interpreter turns into a 500.
 *
 * Pelican converts and stops there. Every operator is lark's, every endpoint
 * description is Pelican's, and neither module learns what the other is for.
 */

/**
 * The frames of a streaming body as a lark [Stream], unread and unbuffered.
 *
 * The element bound is the one difference from [toSource]: lark's `Stream`
 * requires a non-null element, because an operator handed a null cannot promise
 * that a missing value and a failure are different things. A `StreamIn<T>` whose
 * `T` is nullable therefore does not convert, and says so at compile time rather
 * than at the first null frame.
 *
 * Nothing is materialised here, and this takes no actor system: lark supplies
 * one at the far end, to `run`, rather than when the stream is built. That
 * system is the service's own — the one it passed to `start(system)` and closed
 * over — since a handler is handed `Params` and no materializer.
 *
 * ```kotlin
 * ingestOrders handledByOrFail { rows ->
 *     rows.toStream()                                                   // Stream<Nothing, Row>
 *         .mapOrFail { row -> row.customer ?: raise(NoCustomer(row.id)) } // Stream<IngestError, String>
 *         .runFold(Tally(0)) { tally, _ -> Tally(tally.counted + 1) }
 *         .run(system)
 *         .toOutcome(badRows)
 * }
 * ```
 */
fun <T : Any> StreamIn<T>.toStream(): Stream<Nothing, T> = Stream.from(toSource())

/**
 * The [Exit] a lark run completes with, as the [Outcome] a handler answers.
 *
 * `Done` is the value, `Failed` is [failure] — the endpoint's own declared
 * error, which is what fixes the status — and `Died` is rethrown, because a
 * throwable nobody declared is exactly what this library throws rather than
 * returns.
 *
 * Applied to the stage rather than to the [Exit] because `run` completes with
 * one and `handledByOrFail` takes one; converting at the [Exit] would leave the
 * caller a `thenApply` to write, which is where a hand-written `when` goes
 * wrong.
 */
fun <E : Any, A : Any> CompletionStage<Exit<E, A>>.toOutcome(
    failure: ErrorOutput<E>,
): CompletionStage<Outcome<E, A>> =
    thenApply { exit ->
        when (exit) {
            is Exit.Done -> ok(exit.value)
            is Exit.Failed -> failure(exit.error)
            is Exit.Died -> throw exit.cause
        }
    }
