package example

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         // JacksonCodecs, and the schemas the document derives
 *         implementation("io.github.matthewjones372:pelican-jackson:1.0.0")
 *     }
 */

import example.adoption.adoptPet
import example.backends.leaveTip
import example.backends.tipNoticed
import example.whoami.whoAmI
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
    description = "A Kotlin-first Jetty service, described as values."
    servers = listOf("http://localhost:8080")
    // The calls the service sends. They are published under `webhooks` and
    // generated as senders on the client, and no interpreter ever sees them —
    // which is what `webhooks = ` being a setting of its own is for.
    webhooks = allWebhooks
}

/**
 * Spec 0063's example, described the same way. Separate from [ordersSpec]
 * because that document is committed twice over — as a golden document and as
 * a generated client — and adding an example to it rewrites both baselines.
 */
fun adoptionSpec(): ApiSpec = apiSpec(listOf(adoptPet), schemas = JacksonCodecs) {
    title = "Adoption"
    version = "1.0.0"
    description = "Two failures under one status, told apart by a tag."
}

/**
 * Spec 0092's keyed endpoint on its own, so its generated client can be committed and checked without the
 * greetings service's streams and uploads coming with it.
 */
fun tippingSpec(): ApiSpec = apiSpec(listOf(leaveTip), schemas = JacksonCodecs) {
    title = "Tipping"
    version = "1.0.0"
    description = "One call that is answered once, however often it is sent."
}

/** Spec 0093's caller on its own, so its generated client is refused with the 401 it was told about. */
fun whoAmISpec(): ApiSpec = apiSpec(listOf(whoAmI), schemas = JacksonCodecs) {
    title = "Who am I"
    version = "1.0.0"
    description = "One call that needs a caller, and the 401 for one that names nobody."
}

/** Spec 0097's signed endpoint on its own, so its generated client signs what it sends. */
fun tipHooksSpec(): ApiSpec = apiSpec(listOf(tipNoticed), schemas = JacksonCodecs) {
    title = "Tip hooks"
    version = "1.0.0"
    description = "One call that is read only once its signature verifies."
}
