/**
 * The exchange, waited for.
 *
 * A transport answers with a `CompletionStage` because that is the one shape
 * every transport can offer: an asynchronous client serves a blocking caller
 * with a join, and a blocking one cannot serve an asynchronous caller without
 * a thread per call. This is that join.
 *
 * A `CompletionException` is unwrapped on the way out, so what a caller catches
 * is the failure the transport actually raised — an `IOException`, a timeout —
 * rather than a wrapper the choice of transport put around it.
 */
private fun exchange(request: ClientRequest): ClientResponse =
    try {
        transport.send(request).toCompletableFuture().join()
    } catch (failed: CompletionException) {
        throw failed.cause ?: failed
    }

private fun text(request: ClientRequest): TextResponse =
    exchange(request).let { TextResponse(it, it.text()) }

private fun stream(request: ClientRequest): ClientResponse = exchange(request)
