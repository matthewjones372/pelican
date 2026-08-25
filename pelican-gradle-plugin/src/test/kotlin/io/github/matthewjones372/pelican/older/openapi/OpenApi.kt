package io.github.matthewjones372.pelican.older.openapi

import io.github.matthewjones372.pelican.ApiSpec

/**
 * `pelican-openapi` as it was before the OpenAPI version became selectable:
 * one argument, and 3.1.0 whether anybody asked for it or not.
 *
 * Here so that the plugin's fallback to that arity is exercised by this build
 * rather than discovered by a consumer whose library is a release behind their
 * plugin.
 */
fun openApiJson(spec: ApiSpec): String = """{"title":"${spec.title}"}"""
