package dev.pelican.openapi

import dev.pelican.ApiSpec

/** As `pelican-openapi` publishes it. */
fun openApiJson(spec: ApiSpec): String = """{"title":"${spec.title}"}"""
