private val base: String = baseUrl.trimEnd('/')

init {
    require(base.isNotEmpty()) {
        "This client has no base URL: the spec it was generated from declared no server. " +
            "Pass one: ${this::class.simpleName}(baseUrl = \"https://...\", codecs = ...)"
    }
}

/**
 * Every call goes through here. An absent query parameter or header is left
 * off rather than sent empty, so the server applies its own default.
 */
private fun request(
    method: String,
    path: String,
    query: List<Pair<String, Any?>> = emptyList(),
    headerParams: List<Pair<String, Any?>> = emptyList(),
    cookies: List<Pair<String, Any?>> = emptyList(),
    body: HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.noBody(),
    contentType: String? = null,
    multipart: MultipartContent? = null,
): HttpRequest {
    val search = query
        .flatMap { (name, value) -> occurrences(value).map { "${urlEncode(name)}=${urlEncode(it)}" } }
        .joinToString("&")

    val builder = HttpRequest
        .newBuilder(URI.create(base + path + if (search.isEmpty()) "" else "?$search"))
        .timeout(timeout)
        .method(method, multipart?.publisher ?: body)

    (multipart?.contentType ?: contentType)?.let { builder.header("Content-Type", it) }
    headers().forEach { (name, value) -> builder.header(name, value) }
    headerParams.forEach { (name, value) -> plain(value)?.let { builder.header(name, it) } }

    // One header carries all of them, so an absent optional cookie is simply
    // not written rather than sent empty — the same bargain every other
    // optional parameter makes.
    val jar = cookies.flatMap { (name, value) -> occurrences(value).map { "$name=$it" } }
    if (jar.isNotEmpty()) builder.header("Cookie", jar.joinToString("; "))

    return builder.build()
}

private fun text(request: HttpRequest): HttpResponse<String> =
    http.send(request, HttpResponse.BodyHandlers.ofString())

private fun stream(request: HttpRequest): HttpResponse<InputStream> =
    http.send(request, HttpResponse.BodyHandlers.ofInputStream())

private fun HttpResponse<*>.succeeded(): Boolean = statusCode() in 200..299

/**
 * One header off the response, as the string it travelled as. Null when it was
 * not sent — which the declared failures below carry through, rather than
 * insisting on a header the server may have had nothing to say about.
 */
private fun HttpResponse<*>.header(name: String): String? = headers().firstValue(name).orElse(null)

private fun failed(method: String, path: String, response: HttpResponse<String>): Nothing =
    throw ApiCallFailed(response.statusCode(), method, path, response.body())

/** A failed streaming response's body is the one time it is small enough to read whole. */
private fun drain(response: HttpResponse<InputStream>): String =
    response.body().use { it.readBytes().toString(Charsets.UTF_8) }

private fun failedStream(method: String, path: String, response: HttpResponse<InputStream>): Nothing =
    throw ApiCallFailed(response.statusCode(), method, path, drain(response))
