package dev.pelican.openapi

/**
 * Where an API publishes its own documentation, and how the page authenticates.
 *
 * A description, like everything else here: it says which paths the document
 * and the page live at, and nothing about how they get served. That is a
 * backend's job — `pelican-pekko-docs` and `pelican-http4k-docs` each read this
 * value and produce routes for their own server, which is why the setting is
 * written once and means the same thing on both.
 *
 * ```
 * ordersApi().startWithDocs(port = 8080)
 * ordersApi().startWithDocs(port = 8080, docs = Docs(docsPath = "/api-docs"))
 * ```
 */
class Docs(
    /** Where the generated OpenAPI document is served. Null disables it. */
    val openApiPath: String? = "/openapi.json",

    /** Where the Swagger UI page is served. Null disables it. */
    val docsPath: String? = "/docs",

    /**
     * Lets the docs page run the OAuth flow itself, so "Try it out" sends a real
     * token. The redirect target is served alongside the page at
     * `<docsPath>/oauth2-redirect.html`; register that URL with the identity
     * provider.
     */
    val oauth: DocsOAuth? = null,
)
