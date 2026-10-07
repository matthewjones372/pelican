package io.github.matthewjones372.pelican.openapi

/**
 * Where an API publishes its documentation, and how the page authenticates.
 */
class Docs internal constructor(
    /** Where the generated OpenAPI document is served. Null disables it. */
    val openApiPath: String? = DEFAULT_OPENAPI_PATH,

    /** Where the documentation page is served. Null disables it. */
    val docsPath: String? = DEFAULT_DOCS_PATH,

    /** Which page is served at [docsPath]. */
    val ui: DocsUi = DocsUi.SwaggerUi,

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

    /** A Redoc reference served beside the page at [docsPath], reading the same document. See spec 0071. */
    val reference: Reference? = null,

    /** How the page at [docsPath] looks when [ui] is [DocsUi.Redoc]. */
    val redoc: RedocLook = RedocLook.PLAIN,
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
    var ui: DocsUi = DocsUi.SwaggerUi
    var oauth: DocsOAuth? = null
    var version: OpenApiVersion = OpenApiVersion.V3_1_0

    private var reference: Reference? = null
    private var redoc: RedocLook = RedocLook.PLAIN

    /** A Redoc reference at [path], beside the page [ui] serves at [docsPath], in the look [configure] gives it. */
    fun reference(path: String, configure: RedocLookBuilder.() -> Unit = {}) {
        require(path.isNotBlank()) { "docs { reference(...) } needs a path to serve the reference at." }
        reference = Reference(path, RedocLookBuilder().apply(configure).build())
    }

    /** How the page at [docsPath] looks, when it is Redoc's: `docs { ui = DocsUi.Redoc; redoc { ... } }`. */
    fun redoc(configure: RedocLookBuilder.() -> Unit) {
        redoc = RedocLookBuilder().apply(configure).build()
    }

    internal fun build(): Docs {
        reference?.let { page ->
            val taken = if (page.path == docsPath) "docsPath" else "openApiPath"
            require(page.path != docsPath && page.path != openApiPath) {
                "docs { reference(\"${page.path}\") } is already where $taken is served, and one path answers " +
                    "with one page. Give the reference a path of its own."
            }
        }
        // Refused rather than ignored, as an oauth under Redoc is: a look nothing renders is a setting that lies.
        require(redoc.isPlain || ui == DocsUi.Redoc) {
            "docs { redoc { ... } } dresses the page at docsPath when it is Redoc's, and ui is $ui. Set " +
                "ui = DocsUi.Redoc, or give the look to reference(...) to serve Redoc beside it."
        }

        // Refused rather than ignored: Redoc has no "Try it out", so a service
        // that configured a flow here would get a redirect page nothing opens
        // and a token nothing sends.
        require(ui != DocsUi.Redoc || oauth == null) {
            "docs { ui = DocsUi.Redoc } takes no oauth: Redoc sends no requests, " +
                "so there is nothing to authorize. Drop one of the two."
        }

        return Docs(
            openApiPath = openApiPath,
            docsPath = docsPath,
            ui = ui,
            oauth = oauth,
            version = version,
            reference = reference,
            redoc = redoc,
        )
    }
}

/**
 * Which page reads the document.
 *
 * [SwaggerUi] is a console built around "Try it out"; [Redoc] is a read-only
 * three-panel reference. Both render the same document, and neither is
 * configured beyond the title the API already carries.
 */
enum class DocsUi { SwaggerUi, Redoc }

// Written once and read twice: the constructor states them, and [DocsBuilder]
// starts from them.
internal const val DEFAULT_OPENAPI_PATH: String = "/openapi.json"
internal const val DEFAULT_DOCS_PATH: String = "/docs"
