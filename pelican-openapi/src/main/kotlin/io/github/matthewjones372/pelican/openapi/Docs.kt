package io.github.matthewjones372.pelican.openapi

/**
 * Where an API publishes its documentation, and how the page authenticates.
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
