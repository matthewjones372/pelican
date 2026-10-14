package io.github.matthewjones372.pelican

/**
 * HTTP methods, as a plain enum. Backends map this to their own type — Pekko's
 * `HttpMethods.GET`, Ktor's `HttpMethod.Get`, and so on.
 */
enum class Method { GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS }
