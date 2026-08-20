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
        .mapNotNull { (name, value) -> plain(value)?.let { "${urlEncode(name)}=${urlEncode(it)}" } }
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
    val jar = cookies.mapNotNull { (name, value) -> plain(value)?.let { "$name=$it" } }
    if (jar.isNotEmpty()) builder.header("Cookie", jar.joinToString("; "))

    return builder.build()
}

private fun text(request: HttpRequest): HttpResponse<String> =
    http.send(request, HttpResponse.BodyHandlers.ofString())

private fun stream(request: HttpRequest): HttpResponse<InputStream> =
    http.send(request, HttpResponse.BodyHandlers.ofInputStream())

private fun HttpResponse<*>.succeeded(): Boolean = statusCode() in 200..299

private fun failed(method: String, path: String, response: HttpResponse<String>): Nothing =
    throw ApiCallFailed(response.statusCode(), method, path, response.body())

/** A failed streaming response's body is the one time it is small enough to read whole. */
private fun drain(response: HttpResponse<InputStream>): String =
    response.body().use { it.readBytes().toString(Charsets.UTF_8) }

private fun failedStream(method: String, path: String, response: HttpResponse<InputStream>): Nothing =
    throw ApiCallFailed(response.statusCode(), method, path, drain(response))
