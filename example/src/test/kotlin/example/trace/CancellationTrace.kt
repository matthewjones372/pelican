package example.trace

import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.ClientResponse
import io.github.matthewjones372.pelican.ClientTransport
import org.apache.pekko.stream.SubscriptionWithCancelException
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Spec 0052, entry one. A Pekko cancellation carries no stack of its own and
 * never did, so the only way to learn where one surfaces is to record the
 * stack of whoever observed it.
 */
class CancellationTrace(
    private val delegate: ClientTransport,
    private val report: (String) -> Unit = ::println,
) : ClientTransport {

    override fun send(request: ClientRequest): CompletionStage<ClientResponse> {
        val label = "${request.method} ${request.url}"
        val answer = CompletableFuture<ClientResponse>()

        delegate.send(request).whenComplete { response, failure ->
            if (failure == null) {
                answer.complete(
                    ClientResponse(response.status, response.headers, watched("body $label", response.body, report)),
                )
            } else {
                describeCancellation("send $label", failure)?.let(report)
                answer.completeExceptionally(failure)
            }
        }
        return answer
    }
}

/**
 * The observer's own stack, because the cancellation has none: `StageWasCompleted`
 * is a Scala `case object` mixing in `NoStackTrace`, so it is one instance whose
 * frames were never captured.
 */
internal fun describeCancellation(where: String, failed: Throwable): String? {
    val cancellation = generateSequence(failed) { it.cause }
        .firstOrNull { it is SubscriptionWithCancelException.NonFailureCancellation }
        ?: return null

    val chain = generateSequence(failed) { it.cause }.joinToString(" <- ") { it::class.java.name }
    val frames = Thread.currentThread().stackTrace.drop(2).take(14).joinToString("\n    ")
    return "0052-TRACE $where\n  cancellation: ${cancellation::class.java.name}\n  chain: $chain\n    $frames"
}

/** Reports a cancellation seen while reading, then rethrows it untouched. */
internal fun watched(where: String, body: InputStream, report: (String) -> Unit): InputStream =
    object : FilterInputStream(body) {
        override fun read(): Int = traced { super.read() }

        override fun read(b: ByteArray, off: Int, len: Int): Int = traced { super.read(b, off, len) }

        private fun traced(read: () -> Int): Int =
            try {
                read()
            } catch (cancelled: SubscriptionWithCancelException.NonFailureCancellation) {
                report(describeCancellation(where, cancelled) ?: cancelled.toString())
                throw cancelled
            }
    }
