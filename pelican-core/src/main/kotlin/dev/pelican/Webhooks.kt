package dev.pelican

/**
 * A call the service *makes*, described the way a call it answers is described.
 *
 * OpenAPI 3.1's `webhooks` is a map of names to operations the provider sends:
 * an `orderPlaced` a subscriber registered for out of band, arriving at a URL
 * the subscriber chose and this document has never seen. The description is the
 * same description — a method, a body, headers, and what comes back — read in
 * the other direction, which is why it is an [Endpoint] inside rather than a
 * second description model beside it. A parallel one would have had to grow its
 * own inputs, its own outputs and its own reading in all three interpreters,
 * and every one of them would be the same code with the arrows reversed.
 *
 * What a webhook does *not* have is a route. [operation]'s [Endpoint.pathSpec]
 * is empty and nothing reads it: the destination is the subscriber's URL, and
 * it is supplied by whoever sends the call, not by this document. The name is
 * the identity — it is the key OpenAPI files the operation under, and what a
 * generated sender is called.
 *
 * The half that matters for a server is that this is not a [ServerEndpoint] and
 * cannot become one. [Api] takes webhooks in a field of their own, the three
 * interpreters build routes from `endpoints` and never look here, and an
 * [Endpoint] carrying a [Endpoint.webhookName] is refused where an API is bound
 * — see [Api]. A webhook is a call you make; there is nothing on your own
 * server for it to answer.
 */
class Webhook internal constructor(
    /** The key OpenAPI files this under, and the name of the sender generated for it. */
    val name: String,
    /**
     * The description proper. Public because `pelican-openapi` and
     * `pelican-codegen` are separate modules and have to read it — Kotlin's
     * `internal` stops at the module boundary, so hiding it here would mean
     * hiding it from the two readings that exist. Binding it to a handler is
     * therefore expressible and is refused by [Api] rather than by the type
     * system; the alternative was a description model duplicated per direction.
     */
    val operation: Endpoint<*, *>,
) {
    override fun toString() = "webhook $name (${operation.method})"
}

/**
 * What interpreters call this webhook: its declared [Endpoint.operationId], or
 * the name it is filed under.
 *
 * The same string in the document's `operationId` and in the generated sender's
 * method name, for the reason [Endpoint.operationName] is shared — except that
 * a webhook falls back to its name rather than to method-and-path, there being
 * no path to derive one from.
 */
val Webhook.operationName: String get() = operation.operationName

/**
 * Describes a call the service sends rather than serves:
 *
 * ```
 * val orderPlaced = webhook("orderPlaced") {
 *     body(placedEvent)
 *     header(signature)
 *     summary = "Sent when an order reaches a subscriber's endpoint"
 *     empty(status = 204)
 * }
 *
 * ApiSpec(endpoints = allEndpoints, schemas = JacksonCodecs, webhooks = listOf(orderPlaced))
 * ```
 *
 * There is no path to write and no `post(...)` to call: [method] is an argument
 * because that is the whole of what a webhook says about its request line. Say
 * it any other way — a path, a `servers(...)` — and this refuses, because both
 * would be claims about a host the subscriber owns.
 *
 * Only the lens form of [endpoint] is offered, and the omission is the point.
 * The tuple overloads exist to fix a *handler's* signature, and nothing here
 * has a handler: what reads a webhook is the document, which wants the declared
 * inputs, and a generated sender, which takes them as named parameters however
 * they were declared. Inputs go inside the block — `body(...)`, `header(...)`,
 * `query(...)` — as they do for any lens-style endpoint.
 *
 * The output is what the *subscriber* answers with, which is worth saying out
 * loud: it is the one part of the description nobody publishing this document
 * controls. Declaring `empty(204)` says what a receiver is expected to do, the
 * way the rest of the document says what this service will do, and a generated
 * sender reads the answer against it.
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
 * What a webhook may not say, refused where it is written.
 *
 * Each of these would be a description of somewhere else: a path and a server
 * both describe a URL, and the URL a webhook arrives at belongs to whoever
 * subscribed. Publishing them would put this service's spelling of a route into
 * a document about somebody else's endpoint, and a generated sender would then
 * have two answers to where the call goes.
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

    // A streaming output is declared in terms of `StreamOf`/`ByteStream`, whose
    // only purpose is to let a backend bind a handler that produces the stream
    // in its own type. A webhook has no handler on this side at all, so the
    // marker would stand for something that cannot exist — and on the reading
    // end it would leave a sender holding an open connection to a subscriber.
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
