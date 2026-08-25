/**
 * The exchange, awaited.
 *
 * A transport answers with a `CompletionStage` because that is the one shape
 * every transport can offer, whichever HTTP library is underneath. `await` is
 * the bridge from that shape to this one, and it is the whole implementation
 * for two reasons. It resumes with what the transport actually raised — an
 * `IOException`, a timeout — rather than with the `CompletionException` the
 * stage wrapped around it, which is the same unwrapping the blocking form of
 * this client does by hand. And a coroutine cancelled while it is waiting here
 * cancels the underlying `CompletableFuture`, which the transport turns into a
 * cancelled request: a caller that gave up leaves no exchange running behind
 * it.
 *
 * Nothing is blocked while the answer is outstanding, which is the point. What
 * is blocked is the reading of a body once it starts arriving, since a socket
 * read is a socket read — so [text] does that on `Dispatchers.IO` rather than
 * on whichever dispatcher the caller was running on. A `Streamed<T>` cannot be
 * handled here in the same way, because the caller decides when to read the
 * next element; iterate one inside `withContext(Dispatchers.IO)`, or turn it
 * into a `flow { }` with `flowOn(Dispatchers.IO)`.
 */
private suspend fun exchange(request: ClientRequest): ClientResponse =
    transport.send(request).await()

private suspend fun text(request: ClientRequest): TextResponse {
    val response = exchange(request)
    return withContext(Dispatchers.IO) { TextResponse(response, response.text()) }
}

private suspend fun stream(request: ClientRequest): ClientResponse = exchange(request)
