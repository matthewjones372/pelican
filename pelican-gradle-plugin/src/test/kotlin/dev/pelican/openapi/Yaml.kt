package dev.pelican.openapi

import dev.pelican.ApiSpec

/** As `pelican-openapi` publishes it. A separate file, so a separate class. */
fun openApiYaml(spec: ApiSpec): String = "title: ${spec.title}\n"
