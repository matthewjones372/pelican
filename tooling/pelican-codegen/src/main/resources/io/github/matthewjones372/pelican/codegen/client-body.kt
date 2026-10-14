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
 *
 * [deadline] is this client's own, except for a call that streams: those pass
 * null. A deadline is for a call that ends, and the three transports do not
 * bound the same thing — Ktor's request timeout ends the whole exchange, the
 * reading of the body included, while the JDK's and Pekko's are done once the
 * response head arrives. A stream inheriting it therefore died at the deadline
 * on one transport and ran on the other two.
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
    deadline: Duration? = timeout,
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
    ).withTimeout(deadline)
}

private fun TextResponse.succeeded(): Boolean = status in 200..299

private fun ClientResponse.succeeded(): Boolean = status in 200..299

private fun failed(method: Method, path: String, response: TextResponse): Nothing =
    throw ApiCallFailed(response.status, method.name, path, response.body)

/**
 * The body decoded, or a refusal naming the call it came back on.
 *
 * A status this endpoint declared is not a promise about what a proxy in front
 * of it sends: an HTML 404 and a plain-text 502 both arrive here, and a bare
 * codec exception would carry neither the status, the path, nor the bytes that
 * would explain it. [raw] is whatever the codec was handed — a whole body, or
 * one frame of a stream.
 */
private fun <T> BodyCodec<T>.decoded(raw: String, method: Method, path: String, status: Int): T =
    try {
        decodeFromString(raw)
    } catch (failure: Exception) {
        throw ApiCallFailed(status, method.name, path, raw, failure)
    }

/** A failed streaming response's body is the one time it is small enough to read whole. */
private fun drain(response: ClientResponse): String = response.text()

private fun failedStream(method: Method, path: String, response: ClientResponse): Nothing =
    throw ApiCallFailed(response.status, method.name, path, drain(response))
