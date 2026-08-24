package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.ApiSpec

/** As `pelican-openapi` publishes it. */
fun openApiJson(spec: ApiSpec): String = """{"title":"${spec.title}"}"""
