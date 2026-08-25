package io.github.matthewjones372.pelican.openapi

/**
 * Where an API publishes its documentation, and how the page authenticates.
 *
 * Which paths they live at, and nothing about how they are served — each
 * backend's docs module reads this value and produces its own routes, so the
 * setting is written once and means the same thing everywhere.
 */
class Docs(
    /** Where the generated OpenAPI document is served. Null disables it. */
    val openApiPath: String? = "/openapi.json",

    /** Where the Swagger UI page is served. Null disables it. */
    val docsPath: String? = "/docs",

    /**
     * Lets the docs page run the OAuth flow, so "Try it out" sends a real
     * token. Register `<docsPath>/oauth2-redirect.html` with the provider.
     */
    val oauth: DocsOAuth? = null,
)
