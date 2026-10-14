package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.ApiSpec

/** As `pelican-openapi` publishes it. A separate file, so a separate class. */
fun openApiYaml(spec: ApiSpec, version: OpenApiVersion): String =
    "openapi: $version\ntitle: ${spec.title}\n"
