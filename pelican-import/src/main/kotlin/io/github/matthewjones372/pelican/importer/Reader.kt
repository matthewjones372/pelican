package io.github.matthewjones372.pelican.importer

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import java.io.File

/**
 * A document, read as endpoint descriptions.
 *
 * Version differences are gone before this starts — [Swagger2] and
 * [normaliseSchemas] — so what is read is one shape and the only interesting
 * decision is what to refuse. Refusals are recorded per operation rather than
 * thrown, so one run reports every one. See [Problems].
 */
internal class Reader(private val options: ImportOptions) {

    private val problems = Problems()
    private lateinit var document: JsonObj

    /**
     * The document's own named schemas, normalised. Reading a composed schema
     * means resolving the references it is composed of, so the check needs the
     * library as well as the schema in front of it.
     */
    private var declared: JsonObj = JsonObj(emptyMap())

    fun read(file: File): IrApi {
        // The hints go in before anything reads the document, so that what is
        // read is one document with its discriminators stated rather than a
        // document and a list of corrections to remember while reading it.
        val hints = Hints(options.discriminators)
        document = hints.applyTo(normalise(Document.read(file, Remote.forImport(options))))

        val components = document.obj("components")?.obj("schemas") ?: JsonObj(emptyMap())
        declared = JsonObj(components.fields.mapValues { (_, schema) -> normaliseSchema(schema) })

        val found = operations()
        val endpoints = found.routes.mapNotNull { describe(it) }
        // Kept apart from the routes from here on: the two are read the same
        // way and mean opposite directions, and every reader downstream has to
        // be told which of the two it is holding.
        val sent = found.webhooks.mapNotNull { (name, operation) ->
            describe(operation)?.let { IrWebhook(name, it) }
        }
        problems.failIfAny(options.name)

        val described = endpoints + sent.map { it.operation }
        val schemas = used(described, declared)
        // Checked here rather than at the top, because "did this hint matter"
        // is a question about what came out, not about what went in: a hint on
        // a schema only an excluded operation reached has changed nothing.
        hints.failIfUnused(listOf(schemas) + described.flatMap { it.schemas() })

        val info = document.obj("info")
        val required = document.arr("security").let { requirements(it, JsonPath.root / "security") }
        return IrApi(
            title = info?.str("title") ?: "API",
            version = info?.str("version") ?: "1.0.0",
            description = info?.str("description"),
            servers = servers(),
            security = required,
            // A webhook's requirement counts towards the schemes: it is the
            // credential this service presents to a subscriber, and a scheme
            // reached only that way is still a scheme the document declares.
            schemes = schemes(required + described.flatMap { it.security.orEmpty() }),
            schemas = schemas,
            endpoints = endpoints,
            webhooks = sent,
        )
    }

    /**
     * One operation as a description, or null with the reason recorded.
     *
     * Recorded rather than thrown so that one run reports every operation that
     * could not be described — see [Problems]. Shared by the two passes because
     * the refusals are the same refusals: a webhook is read by the same reader
     * that reads a route, which is the whole reason it can be imported at all.
     */
    private fun describe(operation: Operation): IrEndpoint? = try {
        endpoint(operation)
    } catch (e: Unsupported) {
        problems.record(operation.label, operation.id, e.path, e.message)
        null
    }

    /**
     * The named schemas the imported endpoints actually reach, checked.
     *
     * Only the ones reached: `components` is a library, and a type nobody uses
     * is not worth failing over. So excluding an operation excludes the schemas
     * only it used.
     *
     * Reached and unmodellable fails the import outright: a `oneOf` under
     * `Order` is not one operation's problem.
     */
    private fun used(endpoints: List<IrEndpoint>, declared: JsonObj): JsonObj {
        val reachable = Schemas.reachable(endpoints.flatMap { it.schemas() }, declared)
        val schemas = JsonObj(declared.fields.filterKeys { it in reachable })
        schemas.fields.forEach { (name, schema) ->
            try {
                Schemas.check(schema, JsonPath.root / "components" / "schemas" / name, declared, name)
            } catch (e: Unsupported) {
                throw ImportFailure(
                    "The schema `$name` cannot become a Kotlin type, and the operations using it " +
                        "would all have to go with it:\n\n    at ${e.path}\n    ${e.message}",
                    e,
                )
            }
        }
        return schemas
    }

    // ------------------------------------------------------------- the document

    private fun normalise(raw: JsonObj): JsonObj = when {
        raw.str("swagger")?.startsWith("2.") == true -> Swagger2.convert(raw)

        raw.str("openapi") != null -> raw

        else -> throw ImportFailure(
            "This is not an OpenAPI document: it has neither an `openapi` field nor a `swagger` one.",
        )
    }

    private fun servers(): List<String> = serverUrls(document, "servers")

    /**
     * A `servers` list, wherever it sits. One reading for both, or the two
     * would come to disagree about what a templated URL means. [where] is what
     * a failure calls it.
     *
     * A variable with no default fails outright rather than per operation: the
     * document does not say what its own URL is.
     */
    private fun serverUrls(node: JsonObj, where: String): List<String> =
        node.arr("servers").mapIndexedNotNull { i, server ->
            val obj = server as? JsonObj ?: return@mapIndexedNotNull null
            val url = obj.str("url") ?: return@mapIndexedNotNull null
            // A templated URL carries its own defaults, and the defaults are
            // what the document says the server is when nobody chooses.
            // Substituting them is reading the document, not guessing at it.
            obj.obj("variables").entries().fold(url) { substituted, (name, variable) ->
                val default = (variable as? JsonObj)?.str("default")
                    ?: throw ImportFailure("$where[$i] uses {$name}, which declares no default")
                substituted.replace("{$name}", default)
            }
        }

    /**
     * The schemes something actually requires. `securitySchemes` lists what is
     * available, and core collects the published ones from the requirements —
     * so emitting the unused ones would only put unused values in the file.
     */
    private fun schemes(required: List<IrRequirement>): List<IrScheme> {
        val declared = document.obj("components")?.obj("securitySchemes")
        val wanted = required.map { it.scheme }.toSet()

        (wanted - declared.entries().map { it.first }.toSet()).forEach { name ->
            throw ImportFailure(
                "Something requires the security scheme '$name', and the document never declares it.",
            )
        }
        return declared.entries().filter { (name, _) -> name in wanted }.map { (name, raw) ->
            val path = JsonPath.root / "components" / "securitySchemes" / name
            val scheme = deref(raw, path).first
            when (val type = scheme.str("type")) {
                "apiKey" -> IrScheme.ApiKey(
                    name,
                    scheme.str("in") ?: "header",
                    scheme.str("name") ?: name,
                    scheme.str("description"),
                )

                "http" -> IrScheme.Http(
                    name,
                    scheme.str("scheme") ?: "bearer",
                    scheme.str("bearerFormat"),
                    scheme.str("description"),
                )

                "openIdConnect" -> IrScheme.OpenId(
                    name,
                    scheme.str("openIdConnectUrl").orEmpty(),
                    scheme.str("description"),
                )

                "oauth2" -> IrScheme.OAuth2(name, flows(scheme), scheme.str("description"))

                else -> throw ImportFailure("$path declares an unknown security scheme type '$type'")
            }
        }
    }

    private fun flows(scheme: JsonObj): List<IrFlow> = scheme.obj("flows").entries().map { (kind, raw) ->
        val flow = raw as? JsonObj ?: JsonObj(emptyMap())
        IrFlow(
            kind = kind,
            authorizationUrl = flow.str("authorizationUrl"),
            tokenUrl = flow.str("tokenUrl"),
            refreshUrl = flow.str("refreshUrl"),
            scopes = flow.obj("scopes")?.stringMap().orEmpty(),
        )
    }

    // ------------------------------------------------------------- operations

    /** The routes under `paths`, and the calls under `webhooks` by the name each is filed under. */
    private class Found(val routes: List<Operation>, val webhooks: List<Pair<String, Operation>>)

    /**
     * Every operation in the document, named and in document order.
     *
     * `operationId` is required here although the document makes it optional:
     * the generated value, the client's method and the handler stub are all
     * named after it, and deriving one from method and path would produce
     * `getOrdersByOrderIdItems` and rename half the file on a route change.
     *
     * One pass over both sections, since a webhook and a route are two values
     * in one generated file and a shared id is a clash either way.
     */
    private fun operations(): Found {
        val naming = Naming()

        val routes = document.obj("paths").entries().flatMap { (template, rawItem) ->
            item(rawItem, JsonPath.root / "paths" / template, template, null, naming)
        }
        // A webhook is filed under a name and has no path at all, so the
        // template it is read with is empty: there is nothing for a route to be
        // built from, and every reader after this one is told so by that.
        val sent = document.obj("webhooks").entries().flatMap { (name, rawItem) ->
            item(rawItem, JsonPath.root / "webhooks" / name, "", name, naming).map { name to it }
        }

        naming.failIfAny()
        return Found(
            routes.filterNot { it.id in options.exclude },
            sent.filterNot { (_, operation) -> operation.id in options.exclude },
        )
    }

    /** One Path Item Object's operations, wherever the item was filed. */
    private fun item(
        rawItem: JsonValue,
        itemPath: JsonPath,
        template: String,
        webhookName: String?,
        naming: Naming,
    ): List<Operation> {
        val item = deref(rawItem, itemPath).first
        val shared = item.arr("parameters")

        return methods.mapNotNull { method ->
            val operation = item.obj(method) ?: return@mapNotNull null
            val id = operation.str("operationId")
            val where =
                if (webhookName == null) "${method.uppercase()} $template"
                else "webhook $webhookName (${method.uppercase()})"
            if (id == null) {
                naming.unnamed(where)
                return@mapNotNull null
            }
            naming.named(id, where)
            Operation(id, method, template, operation, shared, itemPath / method, webhookName)
        }
    }

    /**
     * The operationIds the document declared, and the two ways they can be
     * wrong. Both are collected across the whole document rather than thrown at
     * the first, for the reason [Problems] collects: the reader's answer is one
     * list of edits to make.
     */
    private class Naming {
        private val missing = mutableListOf<String>()
        private val seen = LinkedHashMap<String, String>()
        private val duplicated = LinkedHashSet<String>()

        fun unnamed(where: String) { missing += where }

        fun named(id: String, where: String) {
            if (seen.put(id, where) != null) duplicated += id
        }

        fun failIfAny() {
            if (missing.isNotEmpty()) throw ImportFailure(unnamedMessage(missing))
            if (duplicated.isNotEmpty()) {
                throw ImportFailure(
                    "Two operations share the operationId ${duplicated.joinToString()}. " +
                        "Each one names a generated value, so they have to differ.",
                )
            }
        }
    }

    private fun endpoint(operation: Operation): IrEndpoint {
        val path = operation.path
        val node = operation.node

        if (node.obj("callbacks")?.fields?.isNotEmpty() == true) {
            unsupported(path / "callbacks", "This operation declares callbacks, which Pelican cannot describe.")
        }

        if (operation.method == "trace") {
            unsupported(path, "TRACE is not a method Pelican routes.")
        }

        val params = Parameters(this, operation).read()
        val responses = Responses(this, operation).read()

        if (operation.webhookName != null) checkWebhook(operation, responses)

        val described = IrEndpoint(
            operationId = operation.id,
            method = operation.method.uppercase(),
            path = operation.template,
            summary = node.str("summary"),
            description = node.str("description"),
            tags = node.strings("tags"),
            deprecated = node.bool("deprecated"),
            params = params,
            // Named against the operation's own path, so "servers[0] uses
            // {region}" is about this operation's list and not the document's.
            servers = serverUrls(node, "${operation.label} servers"),
            body = Bodies(this, operation).read(),
            successes = responses.successes,
            failures = responses.failures,
            responseHeaders = responses.successHeaders,
            security = if (node["security"] == null) null else requirements(node.arr("security"), path / "security"),
        )

        described.schemas().forEach { Schemas.check(it, path, declared) }
        return described
    }

    /**
     * What a webhook may say that a route may, and does not mean here.
     *
     * OpenAPI is silent on what `servers` would mean on a webhook, whose
     * destination is a URL this document has never seen — so reading it would
     * invent a rule and dropping it would be a silent weakening. Recorded per
     * operation, so `exclude` is the way past.
     *
     * A streamed response is refused for core's reason: nothing on this side
     * consumes a stream from a subscriber.
     */
    private fun checkWebhook(operation: Operation, responses: Responses.Result) {
        if (operation.node.arr("servers").isNotEmpty()) {
            unsupported(
                operation.path / "servers",
                "This webhook declares servers, and a webhook is sent to the URL a subscriber registered " +
                    "rather than to a host this document names. There is nothing for the URL here to mean.",
            )
        }

        val streamed = responses.successes.filter { it.streams() }
        if (streamed.isNotEmpty()) {
            unsupported(
                operation.path / "responses",
                "The ${streamed.joinToString { it.status.toString() }} response streams, and this is a " +
                    "webhook: the response is what the *subscriber* sends back to a call this service made, " +
                    "and nothing here consumes a stream from a subscriber.",
            )
        }
    }

    // ---------------------------------------------------------------- shared

    /**
     * Requirements as Pelican reads them: alternatives, each naming one scheme.
     *
     * OpenAPI's inner object is an *and*, which no endpoint description says.
     * Refused rather than approximated: documenting "either credential" for an
     * endpoint needing both is a client that fails against a correct-looking
     * document.
     */
    fun requirements(entries: List<JsonValue>, path: JsonPath): List<IrRequirement> =
        entries.mapIndexedNotNull { i, entry ->
            val fields = (entry as? JsonObj)?.fields.orEmpty()
            when (fields.size) {
                0 -> null

                // `{}` — this alternative is "no credential at all".
                1 -> fields.entries.first().let { (scheme, scopes) ->
                    IrRequirement(
                        scheme,
                        (scopes as? io.github.matthewjones372.pelican.JsonArr)?.items.orEmpty().mapNotNull { scope ->
                            (scope as? JsonStr)?.value
                        },
                    )
                }

                else -> unsupported(
                    path / i,
                    "This requires ${fields.keys.joinToString(" and ")} together. " +
                        "An endpoint description lists alternatives, not combinations.",
                )
            }
        }

    /**
     * Follows a local `$ref`, except to a schema: a schema reference is a name
     * the generated Kotlin takes, so those are left for the type generator.
     * Everything else has no name in the source and is resolved here.
     */
    fun deref(node: JsonValue?, path: JsonPath): Pair<JsonObj, JsonPath> {
        var here = node as? JsonObj ?: JsonObj(emptyMap())
        var where = path
        val visited = mutableSetOf<String>()
        while (true) {
            val ref = here.str("\$ref") ?: return here to where
            if (!visited.add(ref)) unsupported(where, "$ref refers to itself.")
            if (!ref.startsWith("#/")) {
                unsupported(where, "$ref was not resolved, which should not happen after bundling.")
            }
            val steps = ref.removePrefix("#/").split('/').map { it.replace("~1", "/").replace("~0", "~") }
            var target: JsonValue = document
            steps.forEach { step ->
                target = (target as? JsonObj)?.get(step)
                    ?: unsupported(where, "$ref points at nothing in this document.")
            }
            here = target as? JsonObj ?: unsupported(where, "$ref does not point at an object.")
            where = steps.fold(JsonPath.root) { p, step -> p / step }
        }
    }

    private val methods = listOf("get", "put", "post", "delete", "options", "head", "patch", "trace")
}

/** One operation and where it was found, so a failure can say which. */
internal class Operation(
    val id: String,
    val method: String,
    val template: String,
    val node: JsonObj,
    /** The path item's own parameters, which apply to every operation on it. */
    val shared: List<JsonValue>,
    val path: JsonPath,
    /** The `webhooks` key this was filed under, or null for a route under `paths`. */
    val webhookName: String? = null,
) {
    val label: String get() =
        if (webhookName == null) "$id (${method.uppercase()} $template)"
        else "$id (webhook $webhookName, ${method.uppercase()})"
}

private fun unnamedMessage(unnamed: List<String>) = buildString {
    appendLine("${unnamed.size} operation(s) have no operationId:")
    appendLine()
    unnamed.forEach { appendLine("    $it") }
    appendLine()
    append(
        "An operationId is what the generated endpoint value, the client method and the handler " +
            "are named after. Add one to each, and the names stay put when the routes move.",
    )
}
