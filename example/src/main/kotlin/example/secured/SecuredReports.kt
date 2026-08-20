package example.secured

import dev.pelican.*
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.openapi.DocsOAuth
import dev.pelican.openapi.oauth2RedirectPath
import dev.pelican.openapi.openApiJson
import dev.pelican.pekko.*
import dev.pelican.pekko.docs.Docs
import dev.pelican.pekko.docs.startWithDocs
import org.apache.pekko.stream.javadsl.Source
import java.util.Base64

/**
 * An API behind two credentials at once, and a Swagger UI page that can obtain
 * one of them for you.
 *
 *  - `basicAuth()` — a username and password, for the operators who run this
 *    service. Swagger UI has its own dialog for it and sends
 *    `Authorization: Basic ...`.
 *  - `oauth2AuthorizationCode(...)` — an external identity provider, with
 *    scopes, and a redirect URL the docs page is sent back to after the reader
 *    signs in.
 *
 * Two halves, and the join between them is the point:
 *
 *  1. The *description* (sections 1–4). `security(scheme, "scope")` says what a
 *     caller must present. It draws the padlock and writes
 *     `components.securitySchemes` — it checks nothing.
 *  2. The *enforcement* (section 6). One [Filter], registered once on the
 *     [Api], which reads `endpoint.security` — the very list that drew the
 *     padlock — and holds the caller to it.
 *
 * So the document is not a description of the check written alongside it; it is
 * the input to the check. Add an endpoint with `security(companyIdp,
 * "reports:admin")` and it is enforced before it is bound to a handler. Call
 * `noSecurity()` and the filter steps aside, because that is what the empty
 * requirement list means to a reader of the document too.
 *
 * Pelican still validates no credential — it has no idea what yours means, and
 * [Introspection] below is where that knowledge lives. What it does supply is
 * the requirement, in a form something else can enforce, and somewhere to put
 * the enforcing that is not "every handler, by hand".
 */

// ==================================================== 1. the security schemes
//
// Values, declared once. An endpoint holds a reference to one, so asking for a
// scope the scheme never declared fails when this file's `val`s are built —
// at class-init time, not on the first request.

/** The operators' credential: `Authorization: Basic base64(user:password)`. */
val staffLogin = basicAuth(
    name = "staffLogin",
    description = "Operator username and password. Internal endpoints only.",
)

/**
 * The external identity provider. Nothing here is served by this service: the
 * two URLs are the provider's, the scopes are what it will issue, and the
 * tokens are checked by [Introspection] below against what it says.
 *
 * `refreshUrl` is optional and quietly useful — Swagger UI reads it, so a
 * reader whose token expires mid-session gets a new one instead of a 401.
 */
val companyIdp = oauth2AuthorizationCode(
    authorizationUrl = "https://id.example.com/oauth2/authorize",
    tokenUrl = "https://id.example.com/oauth2/token",
    refreshUrl = "https://id.example.com/oauth2/token",
    scopes = mapOf(
        "reports:read" to "Read reports",
        "reports:write" to "File new reports",
        "reports:admin" to "Withdraw a report someone else filed",
    ),
    name = "companyIdp",
    description = "Sign in with your company account.",
)

// ============================================================== 2. the models

data class Report(val id: Long, val title: String, val author: String, val body: String)

data class FileReport(val title: String, val body: String)

/** The failure payload, as a type of this API's own rather than a generic one. */
data class NoSuchReport(val id: Long, val message: String)

/** What `/internal/usage` answers with — visible to staff, nobody else. */
data class Usage(val reports: Int, val callers: Int)

// ============================================ 3. the inputs and the failures

val reportId = pathParam<Long>("reportId", description = "The report's id")

val limit = queryParam("limit", IntCodec.between(1, 100), description = "How many to return").default(20)

val newReport = jsonBody<FileReport>(description = "The report to file")

val reportMissing = errorJson<NoSuchReport>(404, "No report with that id")

/**
 * Where a newly filed report lives. Declared as a value like every input, so
 * the document promises it, the handler sets it through the same object, and
 * setting a header nobody declared throws rather than shipping quietly.
 */
val reportLocation = responseHeader<String>("Location", "Where the report that was just filed lives")

/**
 * How long a throttled caller should wait. Optional, because it is sent only
 * with the 429 — which is what `optional()` says in the document too.
 */
val retryAfter = responseHeader<Long>("Retry-After", "Seconds to wait before trying again").optional()

/**
 * The two ways a credential fails, documented on every endpoint that has one.
 *
 * Note what is *not* declared here: an `Authorization` header parameter. The
 * security scheme already says that header is coming, and declaring it twice
 * would put a text box beside the padlock in Swagger UI and let the two drift.
 * The handler reads the header through the backend's own request instead — see
 * [callerOf].
 */
private fun EndpointBuilder.rejectsBadCredentials() {
    errorResponse(401, "No credential, or one this API does not accept")
    errorResponse(403, "Authenticated, but missing the scope this endpoint names")
    errorResponse(429, "Too many requests", retryAfter)
}

// =========================================================== 4. the endpoints
//
// This section describes; it does not check. Each `security(...)` line is a
// promise the next section has to keep.

/** Open, and documented as open — `security: []` overrides the API-wide default. */
val health = endpoint {
    get("health")
    summary = "Liveness"
    operationId = "health"
    tag("meta")
    noSecurity()
    text()
}

/** No `security(...)` line, so this inherits the API-wide `reports:read`. */
val listReports = endpoint {
    get("reports")
    summary = "List reports"
    operationId = "listReports"
    tag("reports")
    query(limit)
    rejectsBadCredentials()
    jsonArray<Report>()
}

val getReport = endpoint {
    get("reports" / reportId)
    summary = "Fetch one report"
    operationId = "getReport"
    tag("reports")
    security(companyIdp, "reports:read")
    rejectsBadCredentials()
    json<Report>() orFail reportMissing
}

val fileReport = endpoint {
    post("reports")
    summary = "File a report"
    operationId = "fileReport"
    tag("reports")
    security(companyIdp, "reports:write")
    rejectsBadCredentials()
    body(newReport)
    emits(reportLocation)
    json<Report>(status = 201)
}

/**
 * Two requirements, which OpenAPI reads as *either one will do*: an admin token
 * from the identity provider, or an operator with a password. Swagger UI draws
 * both padlocks and sends whichever the reader has authorized.
 */
val withdrawReport = endpoint {
    delete("reports" / reportId)
    summary = "Withdraw a report"
    operationId = "withdrawReport"
    tag("reports")
    security(companyIdp, "reports:admin")
    security(staffLogin)
    rejectsBadCredentials()
    empty(status = 204)
}

/** Basic auth only: no OAuth client of ours should ever reach this. */
val usage = endpoint {
    get("internal" / "usage")
    summary = "Service counters"
    operationId = "usage"
    tag("meta")
    security(staffLogin)
    rejectsBadCredentials()
    json<Usage>()
}

val allSecuredEndpoints: List<Endpoint<*, *>> =
    listOf(health, listReports, getReport, fileReport, withdrawReport, usage)

// =============================================== 5. the store and the "IdP"

object Reports {
    private val filed = mutableListOf(
        Report(1, "Q3 latency", "ada", "p99 is up 12ms since the cache change."),
        Report(2, "Disk pressure", "grace", "Two nodes above 80%."),
    )
    private var nextId = 3L

    fun find(id: Long): Report? = filed.firstOrNull { it.id == id }
    fun list(limit: Int): List<Report> = filed.take(limit)
    fun file(author: String, req: FileReport): Report =
        Report(nextId++, req.title, author, req.body).also { filed += it }
    fun withdraw(id: Long) { filed.removeIf { it.id == id } }
    fun count(): Int = filed.size
}

/** Who a request turned out to be, once its credential checked out. */
data class Caller(val subject: String, val scopes: Set<String>, val staff: Boolean = false)

/**
 * Stands in for the operator directory. A real one hashes the password and
 * compares in constant time; this one is two lines so the example stays about
 * the security *descriptions*.
 */
object StaffDirectory {
    private val passwords = mapOf("ops" to "s3cret", "sre" to "pager-duty")

    fun check(user: String, password: String): Caller? =
        if (passwords[user] == password) Caller(user, emptySet(), staff = true) else null
}

/**
 * Stands in for the identity provider's token introspection endpoint — the call
 * a real service makes to turn an opaque bearer token into a subject and a set
 * of scopes (or verifies a JWT signature against the provider's JWKS instead).
 *
 * These three tokens exist so the example is runnable with curl. Nothing signs
 * them and nothing expires them; a real one is neither guessable nor eternal.
 */
object Introspection {
    private val issued = mapOf(
        "demo-reader" to Caller("ada@example.com", setOf("reports:read")),
        "demo-writer" to Caller("grace@example.com", setOf("reports:read", "reports:write")),
        "demo-admin" to Caller(
            "root@example.com",
            setOf("reports:read", "reports:write", "reports:admin"),
        ),
    )

    fun introspect(token: String): Caller? = issued[token]
}

// ============================================================== 6. the checks
//
// One filter, registered once on the Api. It reads the requirement the endpoint
// already declares for the document, so there is no second list of who may call
// what — and no handler that can forget to ask.

/** Who a request turned out to be. Set by the filter, read by the handlers. */
val caller = attribute<Caller>("caller")

/**
 * The credential on this request, whichever kind it is, or null if it is absent
 * or does not check out.
 *
 * `Params.request` is the Pekko escape hatch — the raw request behind the call.
 * The Ktor and http4k modules each offer the same accessor for their own type,
 * so this is the one function that would change if the backend did.
 */
private fun callerOf(p: Params): Caller? {
    val header = p.request.getHeader("Authorization").orElse(null)?.value() ?: return null
    val (kind, credential) = header.split(' ', limit = 2).takeIf { it.size == 2 } ?: return null
    return when (kind.lowercase()) {
        "bearer" -> Introspection.introspect(credential)
        "basic" -> {
            val decoded = runCatching { String(Base64.getDecoder().decode(credential)) }.getOrNull()
            val (user, password) = decoded?.split(':', limit = 2)?.takeIf { it.size == 2 } ?: return null
            StaffDirectory.check(user, password)
        }
        else -> null
    }
}

/**
 * Whether this caller satisfies one requirement.
 *
 * A requirement names a scheme and, for OAuth, the scopes. Matching on the
 * scheme *value* rather than on its name is what keeps this honest: these are
 * the same `staffLogin` and `companyIdp` objects the endpoints point at, so a
 * scheme renamed in the document cannot silently stop being checked.
 */
private fun Caller.satisfies(requirement: SecurityRequirement): Boolean =
    when (requirement.scheme) {
        staffLogin -> staff
        companyIdp -> requirement.scopes.all { it in scopes }
        else -> false
    }

/**
 * The rule, in one place, for every endpoint.
 *
 * 401 and 403 mean different things and are worth keeping apart: 401 is "I do
 * not know who you are", 403 is "I do, and it is not enough". The `WWW-
 * Authenticate` header on the 401 is what tells a browser which of the two
 * credentials to prompt for.
 *
 * A list of requirements means *any one will do*, which is how OpenAPI reads it
 * and therefore how `withdrawReport` — admin token **or** operator login — has
 * to be read here.
 */
fun enforceDeclaredSecurity(apiWideDefault: List<SecurityRequirement>): Filter = before { p ->
    // Null means the endpoint said nothing and inherits the API's default;
    // an empty list is `noSecurity()`, and deliberate.
    val required = p.endpoint?.security ?: apiWideDefault
    if (required.isEmpty()) return@before

    val who = callerOf(p)
        ?: unauthorized(
            "Present a bearer token or operator credentials",
            challenge = """Bearer realm="reports", Basic realm="reports"""",
        )

    if (required.none { who.satisfies(it) }) {
        forbidden("Needs " + required.joinToString(" or ") { describe(it) })
    }

    p[caller] = who
}

/** What a caller was missing, in the words the document uses. */
private fun describe(requirement: SecurityRequirement): String =
    if (requirement.scopes.isEmpty()) requirement.scheme.name
    else requirement.scopes.joinToString(", ") + " from " + requirement.scheme.name

// ============================================================= 7. the server

val securedRoutes: List<ServerEndpoint> = listOf(

    health handledNow { "ok" },

    listReports streamedNow { p -> Source.from(Reports.list(p[limit])) },

    getReport handledOrFail { p ->
        val id = p[reportId]
        Reports.find(id)?.let { ok(it) } ?: reportMissing(NoSuchReport(id, "No report $id"))
    },

    fileReport handledNow { p ->
        // The subject the identity provider vouched for becomes the author, so
        // the caller cannot claim to be someone else by putting it in the body.
        // `p[caller]` is what the filter worked out; there is no second check
        // here, and no way for this handler to have skipped the first.
        val filed = Reports.file(p[caller].subject, p[newReport])
        setHeader(reportLocation, "/reports/${filed.id}")
        filed
    },

    withdrawReport handledWith { p -> Reports.withdraw(p[reportId]) },

    usage handledNow { Usage(reports = Reports.count(), callers = 3) },
)

/**
 * `security = listOf(companyIdp.requires("reports:read"))` is the API-wide
 * default: every endpoint that says nothing requires that, and the two that do
 * say something replace it. `health` opted out with `noSecurity()`.
 */
val apiWideSecurity = listOf(companyIdp.requires("reports:read"))

fun securedApi(): Api = Api(
    endpoints = securedRoutes,
    codecs = JacksonCodecs,
    title = "Reports",
    version = "1.0.0",
    description = "Reports, behind an operator login and a company identity provider.",
    security = apiWideSecurity,

    // The one line that turns the padlocks above into a rule. Registered on the
    // Api rather than written into each handler, so an endpoint added later is
    // covered by default rather than by remembering.
    filters = listOf(enforceDeclaredSecurity(apiWideSecurity)),

    // And the one line that says these six endpoints are all of them. Leaving
    // one out of `securedRoutes` is now a startup failure rather than a
    // documented endpoint that answers 404.
    covers = allSecuredEndpoints,
)

const val DOCS_PATH = "/api-docs"

/**
 * The docs page as an OAuth client of its own, which is what makes "Try it out"
 * send a real token rather than nothing.
 *
 * No client secret: this page runs in a browser, so a secret shipped to it is
 * not a secret. PKCE replaces it and is on by default — register the docs page
 * with the identity provider as a *public* client.
 *
 * The redirect URL is the one thing to get exactly right on the provider's
 * side: `<origin>/api-docs/oauth2-redirect.html`, served by this service beside
 * the page. [oauth2RedirectPath] is where that path comes from, so what gets
 * printed at start-up and what gets served cannot disagree.
 */
val securedDocs = Docs(
    docsPath = DOCS_PATH,
    oauth = DocsOAuth(
        clientId = "reports-docs-ui",
        usePkce = true,
        // Ticked when the Authorize dialog opens; a reader can still tick more.
        scopes = listOf("reports:read"),
        appName = "Reports API reference",
        // Provider-specific extras ride along on the authorize request. Auth0
        // needs `audience` to issue an access token for an API rather than an
        // ID token; delete this line for a provider that does not want it.
        additionalQueryStringParams = mapOf("audience" to "https://api.example.com/reports"),
    ),
)

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: 8080
    val server = securedApi().startWithDocs(port = port, docs = securedDocs)
    println(
        """
        |Reports API listening on ${server.baseUrl}
        |
        |  Docs     ${server.baseUrl}$DOCS_PATH
        |  Spec     ${server.baseUrl}/openapi.json
        |  Redirect ${server.baseUrl}${oauth2RedirectPath(DOCS_PATH)}
        |           <- register exactly this with the identity provider,
        |              for client id "${securedDocs.oauth?.clientId}"
        |
        |The provider above is fictional, so the Authorize dialog's OAuth flow
        |has nowhere real to go. These stand-in tokens do work:
        |
        |  curl ${server.baseUrl}/reports  -H 'Authorization: Bearer demo-reader'
        |  curl ${server.baseUrl}/reports  -H 'Authorization: Bearer demo-writer' \
        |       -H 'Content-Type: application/json' -d '{"title":"Cold start","body":"90s"}'
        |  curl -X DELETE ${server.baseUrl}/reports/1 -H 'Authorization: Bearer demo-admin'
        |  curl -u ops:s3cret ${server.baseUrl}/internal/usage
        |  curl ${server.baseUrl}/reports                     # 401
        |  curl ${server.baseUrl}/reports -H 'Authorization: Bearer demo-reader' -X POST  # 403
        |
        |Ctrl-C to stop.
        """.trimMargin(),
    )
    Runtime.getRuntime().addShutdownHook(Thread { server.stop().toCompletableFuture().join() })
    Thread.currentThread().join()
}

// ======================================================== 8. the docs, alone
//
// Same descriptions, no server and no handler — the security schemes reach the
// document without anything being started.

fun securedSpec(): ApiSpec = ApiSpec(
    endpoints = allSecuredEndpoints,
    schemas = JacksonCodecs,
    title = "Reports",
    version = "1.0.0",
    description = "Reports, behind an operator login and a company identity provider.",
    security = listOf(companyIdp.requires("reports:read")),
)

fun writeSecuredSpec() = println(securedSpec().openApiJson())
