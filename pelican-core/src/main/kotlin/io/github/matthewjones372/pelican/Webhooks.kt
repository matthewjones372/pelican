package io.github.matthewjones372.pelican

/**
 * A call the service makes — OpenAPI 3.1's `webhooks` — described the way a
 * call it answers is described. It holds an [Endpoint] because that is the same
 * description read in the other direction.
 */
class Webhook internal constructor(
    /** The key OpenAPI files this under, and the name of the sender generated for it. */
    val name: String,
    /**
     * Public because `pelican-openapi` and `pelican-codegen` are separate
     * modules and `internal` stops at the module boundary. Binding it is
     * therefore expressible, and refused by [Api] rather than by the compiler.
     */
    val operation: Endpoint<*, *>,
) {
    override fun toString() = "webhook $name (${operation.method})"
}

/**
 * The declared [Endpoint.operationId], or the name it is filed under — there
 * being no path to derive one from. Shared as [Endpoint.operationName] is.
 */
val Webhook.operationName: String get() = operation.operationName

/**
 * Describes a call the service sends rather than serves.
 */
fun <R> webhook(
    name: String,
    method: Method = Method.POST,
    block: EndpointBuilder.() -> Output<R>,
): Webhook {
    require(name.isNotBlank()) {
        "A webhook is filed under its name, and OpenAPI keys `webhooks` by it, so it has to have one: " +
            "webhook(\"orderPlaced\") { ... }"
    }
    return Webhook(name, describeWebhook(name, method, block).also { validateWebhook(name, it) })
}

/**
 * What a webhook may not say. A path and a server both describe a URL, and the
 * URL a webhook arrives at belongs to whoever subscribed — publishing one would
 * leave a sender with two answers to where the call goes.
 */
private fun validateWebhook(name: String, ep: Endpoint<*, *>) {
    if (ep.pathSpec.segments.isNotEmpty()) {
        error(
            "The webhook '$name' declares the path ${ep.pathSpec.template}, and a webhook has none: " +
                "it is sent to the URL a subscriber registered, which this document has never seen. " +
                "Pass the method as webhook(\"$name\", method = Method.${ep.method}) and leave the path out.",
        )
    }

    if (ep.servers.isNotEmpty()) {
        error(
            "The webhook '$name' declares servers, and the host it reaches is the subscriber's rather " +
                "than one this document could name. The sender is given the URL when it sends.",
        )
    }

    // `StreamOf`/`ByteStream` exist to let a backend bind a handler producing
    // the stream, and a webhook has no handler on this side.
    val streamed = successesOf(ep.output).filter { it.streams() }
    if (streamed.isNotEmpty()) {
        error(
            "The webhook '$name' answers with ${streamed.joinToString()}, and a webhook's response is what " +
                "the *subscriber* sends back to a request this service made. Nothing here consumes a stream " +
                "from a subscriber; declare what the receiver returns — an empty(204), or a small json<T>().",
        )
    }
}

private fun successesOf(out: Output<*>): List<Output<*>> =
    if (out is FallibleOutput<*, *>) out.successes else listOf(out)
