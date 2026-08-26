package example

import io.github.matthewjones372.pelican.ApiSpec
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.jackson.JacksonCodecs

/**
 * The spec, as a value. Note what this file does not import: no server, no
 * handler, and no generator either. The descriptions in Endpoints.kt plus a
 * way to describe the payload types are enough.
 *
 * Both readings of it are Gradle tasks — `generateOrdersDocument` writes the
 * OpenAPI document and `generateOrdersClient` writes the Kotlin client. The
 * plugin loads this function by name off the module's own classpath, so there
 * is no `main` here to keep in step with the build file. The hidden endpoint is
 * absent from both for the same reason: it is not published.
 */
// Documentation needs only a SchemaSource, never a codec that can encode.
fun ordersSpec(): ApiSpec = apiSpec(allEndpoints, schemas = JacksonCodecs) {
    title = "Orders"
    version = "1.0.0"
    description = "A Kotlin-first Pekko HTTP service, described as values."
    servers = listOf("http://localhost:8080")
    // The calls the service sends. They are published under `webhooks` and
    // generated as senders on the client, and no interpreter ever sees them —
    // which is what `webhooks = ` being a setting of its own is for.
    webhooks = allWebhooks
}
