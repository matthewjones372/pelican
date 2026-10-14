package io.github.matthewjones372.pelican.streams.jetty

import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.blocking
import io.github.matthewjones372.pelican.ErrorOutput
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.StreamIn
import io.github.matthewjones372.pelican.jetty.reader
import io.github.matthewjones372.pelican.ok
import java.util.concurrent.CompletionStage

/**
 * The frames of a streaming body as a lark [Stream], read one at a time as the run pulls them. A run's `stop()`
 * wakes a read blocked on the upload, and however the run ends the upload is released.
 */
fun <T : Any> StreamIn<T>.toStream(): Stream<Nothing, T> =
    Stream.blocking(
        open = { reader() },
        next = { if (it.hasNext()) it.next() else null },
        wake = { it.wake() },
        close = { it.close() },
    )

/**
 * The [Exit] a lark run completes with, as the [Outcome] a handler answers: `Done` is the value, `Failed` is
 * [failure], and `Died` is rethrown, since a throwable nobody declared is not the declared failure.
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
