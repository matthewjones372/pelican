package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.ApiSpec

/** As `pelican-openapi` publishes it. */
fun openApiJson(spec: ApiSpec, version: OpenApiVersion): String =
    """{"openapi":"$version","title":"${spec.title}"}"""

/** The enum the real one takes, named the same way, because the lookup is by type. */
enum class OpenApiVersion(private val field: String) {
    V3_1_0("3.1.0"),
    V3_2_0("3.2.0"),
    ;

    override fun toString(): String = field
}
