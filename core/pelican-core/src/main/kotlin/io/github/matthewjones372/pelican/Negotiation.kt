package io.github.matthewjones372.pelican

/**
 * Nothing this endpoint produces is something the caller will accept.
 *
 * Raised before the handler runs, so a handler that charges a card does not do
 * it for a response nobody will read. [produced] goes in the body, which RFC
 * 9110 allows and is the caller's only way to find out.
 */
class NotAcceptable(val produced: Set<String>) : RuntimeException(
    "This endpoint produces ${produced.joinToString()}, and the request's Accept header takes none of them",
)
