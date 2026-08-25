/**
 * Every call goes through here. An absent query parameter or header is left
 * off rather than sent empty, so the server applies its own default.
 *
 * What comes out is a `ClientRequest`, which is core's own vocabulary rather
 * than any HTTP library's: the URL is assembled and encoded here, where the
 * description is, and the transport is left with nothing to decide.
 *
 * [origin] is this client's own base URL, except for a method the document said
 * is served somewhere else: those pass the host that operation's `servers`
 * block named, since that is where the service answers it. A webhook passes the
 * subscriber's URL, which nothing in the document could have known.
 *
 * [standingHeaders] defaults to the ones this client sends with everything —
 * and a webhook passes none, because those are the credential the client
 * presents to the API and a subscriber's endpoint is not the API.
 */
private fun request(
    method: Method,
    path: String,
    query: List<Pair<String, Any?>> = emptyList(),
    headerParams: List<Pair<String, Any?>> = emptyList(),
    cookies: List<Pair<String, Any?>> = emptyList(),
    body: ClientRequest.Body = ClientRequest.Body.Empty,
    contentType: String? = null,
    multipart: MultipartContent? = null,
    origin: String = base,
    standingHeaders: Map<String, String> = headers(),
): ClientRequest {
    val search = query
        .flatMap { (name, value) -> occurrences(name, value).map { "${urlEncode(name)}=${urlEncode(it)}" } }
        .joinToString("&")

    // One header carries all of them, so an absent optional cookie is simply
    // not written rather than sent empty — the same bargain every other
    // optional parameter makes.
    val jar = cookies.flatMap { (name, value) -> occurrences(name, value).map { "$name=$it" } }

    val sent =
        listOfNotNull((multipart?.contentType ?: contentType)?.let { "Content-Type" to it }) +
            standingHeaders.map { (name, value) -> name to value } +
            headerParams.mapNotNull { (name, value) -> plain(value)?.let { name to it } } +
            (if (jar.isEmpty()) emptyList() else listOf("Cookie" to jar.joinToString("; ")))

    return ClientRequest(
        method = method,
        url = origin + path + if (search.isEmpty()) "" else "?$search",
        headers = sent,
        body = multipart?.body ?: body,
        timeout = timeout,
    )
}

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

private fun TextResponse.succeeded(): Boolean = status in 200..299

private fun ClientResponse.succeeded(): Boolean = status in 200..299

private fun failed(method: Method, path: String, response: TextResponse): Nothing =
    throw ApiCallFailed(response.status, method.name, path, response.body)

/** A failed streaming response's body is the one time it is small enough to read whole. */
private fun drain(response: ClientResponse): String = response.text()

private fun failedStream(method: Method, path: String, response: ClientResponse): Nothing =
    throw ApiCallFailed(response.status, method.name, path, drain(response))
