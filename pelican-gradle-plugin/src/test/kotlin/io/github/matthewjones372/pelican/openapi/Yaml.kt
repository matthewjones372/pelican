package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.ApiSpec

/** As `pelican-openapi` publishes it. A separate file, so a separate class. */
fun openApiYaml(spec: ApiSpec): String = "title: ${spec.title}\n"
