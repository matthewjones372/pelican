package example

import dev.pelican.ApiSpec
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.openapi.openApiJson
import java.io.File

/**
 * The documentation half. Note what this file does not import: no server, no
 * handler. The descriptions in Endpoints.kt plus a way to describe the
 * payload types are enough.
 */
fun ordersSpec(): ApiSpec = ApiSpec(
    endpoints = allEndpoints,
    // Documentation needs only a SchemaSource, never a codec that can encode.
    schemas = JacksonCodecs,
    title = "Orders",
    version = "1.0.0",
    description = "A Kotlin-first Pekko HTTP service, described as values.",
    servers = listOf("http://localhost:8080"),
)

/** Entry point for the `generateOpenApi` Gradle task. */
fun main(args: Array<String>) {
    val target = File(args.firstOrNull() ?: "openapi.json")
    target.parentFile?.mkdirs()
    target.writeText(ordersSpec().openApiJson())
    println("Wrote ${target.absolutePath}")
}
