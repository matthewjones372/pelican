package io.github.matthewjones372.pelican.openapi

/**
 * Where an API publishes its documentation, and how the page authenticates.
 */
class Docs internal constructor(
    /** Where the generated OpenAPI document is served. Null disables it. */
    val openApiPath: String? = DEFAULT_OPENAPI_PATH,

    /** Where the Swagger UI page is served. Null disables it. */
    val docsPath: String? = DEFAULT_DOCS_PATH,

    /**
     * Lets the docs page run the OAuth flow, so "Try it out" sends a real
     * token. Register `<docsPath>/oauth2-redirect.html` with the provider.
     */
    val oauth: DocsOAuth? = null,

    /**
     * Which revision of the specification the served document is written
     * against. The default is [OpenApiVersion.V3_1_0], for the reasons on that
     * type; a service whose readers all run tooling that has caught up can say
     * `docs { version = OpenApiVersion.V3_2_0 }` and get the document that
     * describes its cookies and its streams correctly.
     */
    val version: OpenApiVersion = OpenApiVersion.V3_1_0,
)

/**
 * Where this API publishes itself, and the defaults above where the block says
 * nothing. `docs()` on its own serves `/openapi.json` and `/docs`.
 */
fun docs(configure: DocsBuilder.() -> Unit = {}): Docs = DocsBuilder().apply(configure).build()

/** What [docs]'s block writes into. Each setting is documented on [Docs]. */
class DocsBuilder internal constructor() {

    var openApiPath: String? = DEFAULT_OPENAPI_PATH
    var docsPath: String? = DEFAULT_DOCS_PATH
    var oauth: DocsOAuth? = null
    var version: OpenApiVersion = OpenApiVersion.V3_1_0

    internal fun build(): Docs = Docs(
        openApiPath = openApiPath,
        docsPath = docsPath,
        oauth = oauth,
        version = version,
    )
}

// Written once and read twice: the constructor states them, and [DocsBuilder]
// starts from them.
internal const val DEFAULT_OPENAPI_PATH: String = "/openapi.json"
internal const val DEFAULT_DOCS_PATH: String = "/docs"
