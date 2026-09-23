package io.github.matthewjones372.pelican.streams

import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.from
import io.github.matthewjones372.pelican.StreamIn
import io.github.matthewjones372.pelican.pekko.toSource

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
 * ingestOrders handledBy { rows ->
 *     rows.toStream()                                                   // Stream<Nothing, Row>
 *         .mapOrFail { row -> row.customer ?: raise(NoCustomer(row.id)) } // Stream<IngestError, String>
 *         .runFold(0) { seen, _ -> seen + 1 }
 *         .run(system)
 *         .thenApply { exit ->
 *             when (exit) {
 *                 is Exit.Done -> Tally(exit.value)
 *                 is Exit.Failed -> Tally(0)
 *                 is Exit.Died -> throw exit.cause
 *             }
 *         }
 * }
 * ```
 *
 * `run` completes with an [io.github.matthewjones372.lark.stream.Exit] rather
 * than a failed stage, so the `when` over `Done`, `Failed` and `Died` is where
 * a handler decides what each one answers.
 */
fun <T : Any> StreamIn<T>.toStream(): Stream<Nothing, T> = Stream.from(toSource())
