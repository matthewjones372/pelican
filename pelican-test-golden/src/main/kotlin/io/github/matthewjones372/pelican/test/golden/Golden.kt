package io.github.matthewjones372.pelican.test.golden

import io.github.matthewjones372.pelican.ApiSpec
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.Webhook
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.openapi.ApiChange
import io.github.matthewjones372.pelican.openapi.Compatibility
import io.github.matthewjones372.pelican.openapi.apiChanges
import io.github.matthewjones372.pelican.openapi.openApi
import io.github.matthewjones372.pelican.openapi.report
import io.github.matthewjones372.pelican.parseJson
import io.github.matthewjones372.pelican.renderPretty
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.Transport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Set to `true` to accept every snapshot this run produces.
 *
 * Gradle does not hand its own `-D` flags to the test JVM, so a build wanting
 * this one has to pass it on:
 *
 * ```
 * tasks.test {
 *     systemProperty("pelican.golden.update", providers.systemProperty("pelican.golden.update").getOrElse("false"))
 * }
 * ```
 *
 * [UPDATE_ENVIRONMENT] needs no such line, which is why it is also read.
 */
const val UPDATE_PROPERTY: String = "pelican.golden.update"

/**
 * The same switch as an environment variable, because a test JVM inherits the
 * environment it was forked from: `PELICAN_GOLDEN_UPDATE=true ./gradlew test`
 * works against a build that was never configured for any of this.
 */
const val UPDATE_ENVIRONMENT: String = "PELICAN_GOLDEN_UPDATE"

/**
 * Header names dropped before a response is recorded, because they differ
 * between two runs of the same test and would fail it for saying so.
 */
val VOLATILE_HEADERS: Set<String> = setOf("Date", "Server", "Connection", "Keep-Alive")

/**
 * Snapshots of what the descriptions publish, checked in beside the tests.
 *
 * The typed client is deliberately blind to the URL — `call(getOrder, 1L)` says
 * nothing about `/orders/1`, so renaming an input breaks compilation instead of
 * quietly starting to 404. That blindness is also the gap: the client builds
 * its request from the same description the server routes on, so a rename moves
 * both at once and the suite stays green while the callers already deployed
 * against the old name do not.
 *
 * A golden file is the second reader. It records what the descriptions publish
 * into the repository, so the next run has the *published* contract to compare
 * against rather than only the source tree:
 *
 * ```
 * private val golden = Golden()
 *
 * @Test fun `every endpoint publishes what it published`() {
 *     golden.operations(ordersSpec())
 * }
 *
 * @Test fun `fetching one order builds the call callers hold`() {
 *     golden.request("get-order", app.request(getOrder, 1L))
 * }
 * ```
 *
 * A contract recording is compared as a document and not as text. A new
 * required field, a deleted endpoint, a status that stopped being declared and
 * a response field that disappeared each fail the test, naming the caller they
 * break; a new optional parameter, a new endpoint or a rewritten summary
 * updates the golden in place and passes, because a check that fails on things
 * nobody has to act on is a check people learn to accept without reading. See
 * [Compatibility] for what counts as which, and pass `strict = true` to fail on
 * any change at all.
 *
 * The wire recordings — [request], [response], [exchange] — are text, and any
 * difference in them fails: those bytes *are* the contract, and there is no
 * safe change to the address a caller has to type.
 *
 * The first run has nothing to compare against, so it writes `<name>_new.<ext>`
 * and fails: a snapshot nobody read is not a reviewed contract. Rename it once
 * you have read it. A later mismatch writes `<name>_changed.<ext>`, leaving
 * both files on disk to diff.
 */
class Golden(
    /** Resolved against the module directory, which is a test's working directory under Gradle. */
    private val directory: Path = Paths.get("src", "test", "resources", "golden"),
    /** Rewrites every golden this run touches instead of comparing. See [UPDATE_PROPERTY]. */
    private val update: Boolean = System.getProperty(UPDATE_PROPERTY).toBoolean() ||
        System.getenv(UPDATE_ENVIRONMENT).toBoolean(),
    /** Response headers left out of a recording; see [VOLATILE_HEADERS]. */
    private val ignoringHeaders: Set<String> = VOLATILE_HEADERS,
    /**
     * Fails on every difference, not only on the ones that break a caller.
     *
     * The default is deliberately the other way round: a service that adds an
     * optional parameter has not changed anybody's contract, and a test that
     * goes red for it trains its author to run with [UPDATE_ENVIRONMENT] set,
     * which is how a real break gets accepted by reflex. Take this where the
     * document is a published artifact in its own right and every line of it is
     * reviewed.
     */
    private val strict: Boolean = false,
) {

    /**
     * Records the OpenAPI document the descriptions generate, and compares the
     * next run against it as a contract.
     *
     * One file covering every endpoint, which is the artifact consumers
     * generate their clients from. [operations] is the same comparison split
     * per endpoint, so a failure names the endpoint rather than a line number.
     */
    fun document(api: ApiSpec, name: String = "openapi"): Unit = contract(name, api.openApi())

    /**
     * Records every endpoint the spec publishes, one file each, without a line
     * of test code per endpoint.
     *
     * The endpoints are already a list, so the tests do not have to be written
     * out: this walks them and snapshots what each one publishes — its path,
     * its parameters, the statuses it declares and the schemas they carry.
     * Adding an endpoint adds its golden on the next run, and deleting one
     * leaves a file whose absence from this run is the diff that says so.
     *
     * ```
     * @Test fun `every endpoint publishes what it published`() {
     *     golden.operations(ordersSpec())
     * }
     * ```
     *
     * One file per operation rather than one for the API, because a failure
     * should name the endpoint that changed: `golden/operations/placeOrder.json`
     * in a diff says more than line 812 of a document does. [document] is the
     * other reading, for the whole-API view a consumer generates from.
     *
     * Every operation is compared before anything is thrown, so one run reports
     * every endpoint that broke rather than the first.
     */
    fun operations(api: ApiSpec, folder: String = "operations") {
        val slices = api.endpoints.filterNot { it.hidden }.map { fileName(it) to sliceOf(api, endpoint = it) } +
            api.webhooks.filterNot { it.operation.hidden }.map { "webhook-${it.name}" to sliceOf(api, webhook = it) }

        val verdicts = slices.map { (name, slice) -> compare(within(folder, name), slice) }
        val changes = verdicts.filterIsInstance<Verdict.Broken>().flatMap { it.changes } +
            retired(folder, slices.map { it.first })
        val unreviewed = verdicts.filterIsInstance<Verdict.Unreviewed>()

        if (changes.isEmpty() && unreviewed.isEmpty()) return

        val reported = if (changes.isEmpty()) null else {
            changes.report("${api.title} ${api.version}") + "\n\n" + accepting(folder)
        }

        throw AssertionError(listOfNotNull(reported, proposals(unreviewed)).joinToString("\n\n"))
    }

    /**
     * The endpoints that are not in the spec any more.
     *
     * Everything else here compares what the descriptions produce, and a
     * deleted endpoint produces nothing — so it would be the one change that
     * passes silently, which is the opposite of what it costs. A golden with
     * nothing left to regenerate it *is* the deletion, and it is read as one.
     */
    private fun retired(folder: String, produced: List<String>): List<ApiChange> {
        val where = if (folder.isEmpty()) directory else directory.resolve(folder)
        if (Files.notExists(where)) return emptyList()

        val orphans = Files.list(where).use { files ->
            files.map { it.fileName.toString() }
                .filter { it.endsWith(".json") && !it.endsWith("_new.json") && !it.endsWith("_changed.json") }
                .map { it.removeSuffix(".json") }
                .toList()
        }.filterNot { it in produced }.sorted()

        if (update) {
            orphans.forEach { Files.deleteIfExists(where.resolve("$it.json")) }
            return emptyList()
        }

        return orphans.map { name -> deletion(where.resolve("$name.json"), name) }
    }

    /**
     * A recording with nothing left to produce it, read as what it is.
     *
     * The file says which kind it was, and the two kinds cost different people
     * something different: a route that is gone is a 404 for its callers, and a
     * webhook that is gone is a subscriber who stops being told.
     */
    private fun deletion(file: Path, name: String): ApiChange {
        val sent = recorded(file, "webhooks")
        val served = recorded(file, "paths")

        return if (sent != null) {
            ApiChange(
                Compatibility.BREAKING,
                sent,
                "the webhook is gone from the descriptions",
                "every subscriber registered for it stops being told — delete $file to retire it",
            )
        } else {
            ApiChange(
                Compatibility.BREAKING,
                served ?: name,
                "the endpoint is gone from the descriptions",
                "every caller still holding it gets a 404 — delete $file to retire it",
            )
        }
    }

    /**
     * The call a recording describes, named the way its counterparty would name
     * it — which is also how the file says whether it was a route or a webhook.
     */
    private fun recorded(golden: Path, section: String): String? {
        val document = runCatching { parseJson(Files.readString(golden)) as? JsonObj }.getOrNull() ?: return null
        val entries = (document[section] as? JsonObj)?.fields ?: return null
        val (key, item) = entries.entries.firstOrNull() ?: return null
        val method = (item as? JsonObj)?.fields?.keys?.firstOrNull() ?: return null
        return "${method.uppercase()} $key"
    }

    /**
     * Records one endpoint's slice of the document, named as [operations] names it.
     */
    fun operation(api: ApiSpec, endpoint: Endpoint<*, *>, folder: String = "operations") =
        contract(within(folder, fileName(endpoint)), sliceOf(api, endpoint = endpoint))

    /**
     * Records the call an endpoint builds, without sending it.
     *
     * `shouldBuild` pins the request line against a literal in the test; this
     * pins the headers and the encoded body with it, which is where a codec
     * change or a list style hides.
     */
    fun request(name: String, request: RequestSpec): Unit = text(name, "http", request.wireText())

    /** Records a response as received, minus [ignoringHeaders]. */
    fun response(name: String, response: ResponseSpec): Unit =
        text(name, "http", response.wireText(ignoringHeaders))

    /** Records both halves of one exchange in a single file, which is how a reviewer reads them. */
    fun exchange(name: String, request: RequestSpec, response: ResponseSpec): Unit = text(
        name,
        "http",
        request.wireText() + "\n\n" + EXCHANGE_SEPARATOR + "\n\n" + response.wireText(ignoringHeaders),
    )

    /**
     * Sends the call and records what went out and what came back.
     *
     * ```
     * golden.exchange("get-order", app, getOrder, 1L)
     * ```
     */
    fun <I> exchange(name: String, client: ApiClient, endpoint: Endpoint<I, *>, input: I): Unit =
        exchange(name, client.request(endpoint, input), client.response(endpoint, input))

    /**
     * Compares a document against the one recorded under `<name>.json` — as a
     * contract, which is what makes this more than a diff.
     *
     * A difference is not a failure by itself. The recorded document is what
     * callers were promised, so the two are compared as OpenAPI documents and
     * every difference is classified: a new required field, a deleted endpoint,
     * a status that stopped being declared or a response field that disappeared
     * fails the test, and a new optional parameter, a new endpoint or a
     * rewritten description rewrites the golden and passes.
     *
     * Rewriting it is the point rather than a shortcut. The golden's job is to
     * be the contract the *next* change is measured against, and a file that
     * goes stale because everyone stopped reading its failures cannot do that
     * job. `strict = true` fails on every difference, for a document that is a
     * published artifact in its own right.
     */
    fun contract(name: String, proposed: JsonObj) {
        when (val verdict = compare(name, proposed)) {
            is Verdict.Fine -> Unit

            is Verdict.Unreviewed -> throw AssertionError(proposals(listOf(verdict)))

            is Verdict.Broken -> throw AssertionError(
                verdict.changes.report(verdict.golden.fileName.toString()) + "\n\n" +
                    accepting(verdict.golden, verdict.changed),
            )
        }
    }

    /** What one recording came to, so a caller comparing many can report them together. */
    private sealed interface Verdict {
        /** Unchanged, or changed in a way no caller has to act on — in which case the golden moved. */
        data object Fine : Verdict

        /** There is no golden yet, and what this run produced is waiting to be read. */
        data class Unreviewed(val golden: Path, val proposed: Path) : Verdict

        data class Broken(val golden: Path, val changed: Path, val changes: List<ApiChange>) : Verdict
    }

    private fun compare(name: String, proposed: JsonObj): Verdict {
        val rendered = proposed.renderPretty() + "\n"
        val golden = directory.resolve("$name.json")
        val unreviewed = directory.resolve("${name}_new.json")
        val changed = directory.resolve("${name}_changed.json")

        if (update) {
            write(golden, rendered)
            clear(unreviewed, changed)
            return Verdict.Fine
        }

        if (Files.notExists(golden)) {
            write(unreviewed, rendered)
            return Verdict.Unreviewed(golden, unreviewed)
        }

        val (published, recorded) = read(golden)
        if (recorded == rendered) {
            clear(unreviewed, changed)
            return Verdict.Fine
        }

        val changes = apiChanges(published, proposed)

        if (changes.none { it.compatibility == Compatibility.BREAKING } && !strict) {
            // Nothing a caller has to act on, so the contract moves forward
            // rather than the test going red. The file's diff is the record.
            write(golden, rendered)
            clear(unreviewed, changed)
            return Verdict.Fine
        }

        write(changed, rendered)
        return Verdict.Broken(golden, changed, changes)
    }

    private fun within(folder: String, name: String) = if (folder.isEmpty()) name else "$folder/$name"

    /** The recorded document, and the text it was recorded as, normalised for comparison. */
    private fun read(golden: Path): Pair<JsonObj, String> {
        val text = Files.readString(golden).replace("\r\n", "\n").trimEnd('\n') + "\n"
        val document = runCatching { parseJson(text) as? JsonObj }.getOrNull()
            ?: throw AssertionError(
                "${golden.toAbsolutePath()} is not an OpenAPI document this can read back. It is a golden " +
                    "recorded by `contract`, `document` or `operations`, which write JSON; if it was written " +
                    "by hand or by an older version, delete it and let the next run propose it again.",
            )
        return document to text
    }

    /**
     * Compares [actual] against `<name>.<extension>` as text, where any
     * difference fails.
     *
     * This is what the wire recordings are written in terms of, and it is
     * public because a project with a derived artifact of its own — a generated
     * client, a rendered changelog — wants the same review workflow for it.
     *
     * No classification here, and none possible: these bytes are not a document
     * with a meaning this can read. For an OpenAPI document, [contract] is the
     * one that knows a new optional parameter from a new required one.
     */
    fun text(name: String, extension: String, actual: String) {
        val recorded = actual.trimEnd('\n') + "\n"
        val golden = directory.resolve("$name.$extension")
        val proposed = directory.resolve("${name}_new.$extension")
        val changed = directory.resolve("${name}_changed.$extension")

        if (update) {
            write(golden, recorded)
            clear(proposed, changed)
            return
        }

        if (Files.notExists(golden)) {
            write(proposed, recorded)
            throw AssertionError(proposals(listOf(Verdict.Unreviewed(golden, proposed))))
        }

        // Normalised the same way the recording is: a golden that lost its
        // trailing newline to an editor is the same contract, and failing a
        // build over that would teach people to ignore this.
        val committed = Files.readString(golden).replace("\r\n", "\n").trimEnd('\n') + "\n"
        if (committed == recorded) {
            clear(proposed, changed)
            return
        }

        write(changed, recorded)
        throw AssertionError(
            "${golden.fileName} is not what the descriptions produce any more.\n\n" +
                differences(committed.lines(), recorded.lines()) + "\n\n" +
                accepting(golden, changed),
        )
    }

    // ------------------------------------------------------------- the notes
    //
    // What is printed under the report: the two or three lines that say what to
    // do next. Separate from the report itself, which is the library's rendering
    // of the changes and knows nothing about files.

    private fun accepting(golden: Path, changed: Path): String =
        "  What this run produced is in ${changed.toAbsolutePath()}, beside the golden, so the two can be " +
            "diffed.\n  If the change is meant — a new major version, callers told in advance — accept it with " +
            "`mv ${changed.fileName} ${golden.fileName}`, or rerun with $UPDATE_ENVIRONMENT=true."

    private fun accepting(folder: String): String {
        val where = if (folder.isEmpty()) directory else directory.resolve(folder)
        return "  What this run produced is beside each golden, as `*_changed.json` in ${where.toAbsolutePath()}." +
            "\n  If the changes are meant, `mv` each over its golden, or rerun with $UPDATE_ENVIRONMENT=true."
    }

    private fun proposals(unreviewed: List<Verdict.Unreviewed>): String? {
        if (unreviewed.isEmpty()) return null

        val listed = unreviewed.joinToString("\n") { "    ${it.proposed.fileName}" }
        val subject = if (unreviewed.size == 1) "There is no golden file yet" else
            "${unreviewed.size} of these have no golden file yet"

        return "  $subject, so what this run produced was written beside where they belong:\n\n$listed\n\n" +
            "  Read them — they are the contract you are about to promise — then drop the `_new` from each " +
            "name and commit them.\n  Nothing is accepted on your behalf, because a snapshot nobody read is " +
            "not a review. $UPDATE_ENVIRONMENT=true accepts the lot, which is the reasonable thing to do for " +
            "an API that already exists."
    }

    private fun write(file: Path, content: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun clear(vararg files: Path) = files.forEach { Files.deleteIfExists(it) }
}

/**
 * The document as it would read if this were the only operation in it.
 *
 * Generated by the same interpreter as the whole document rather than by
 * slicing one up, so an operation's golden and the published document cannot
 * disagree about what the operation says. The preamble — the OpenAPI version,
 * the title, the servers — is dropped: it is identical in every file, and it
 * belongs to the API rather than to the endpoint. `document` is where a change
 * to it shows up.
 */
private fun sliceOf(api: ApiSpec, endpoint: Endpoint<*, *>? = null, webhook: Webhook? = null): JsonObj {
    val alone = apiSpec(listOfNotNull(endpoint), api.schemas) {
        title = api.title
        version = api.version
        // Kept, unlike the rest of the preamble: what an endpoint requires when
        // it declares nothing is what the API requires, and a caller reading
        // this file is reading the credential it has to send.
        security = api.security
        webhooks = listOfNotNull(webhook)
    }

    return JsonObj(alone.openApi().fields - PREAMBLE)
}

/** Identical in every operation's file, and [Golden.document] is where a change to it belongs. */
private val PREAMBLE = setOf("openapi", "info", "servers")

/**
 * What an operation's file is called: the `operationId` where there is one,
 * since that is the name the generated clients already use, and the method and
 * path template where there is not.
 */
private fun fileName(endpoint: Endpoint<*, *>): String =
    endpoint.operationId ?: (
        endpoint.method.name.lowercase() + "-" +
            endpoint.pathSpec.template.replace(Regex("[{}/]+"), "-").trim('-').ifEmpty { "root" }
        )

/**
 * The first few differing lines, with their numbers.
 *
 * A whole-file diff of an OpenAPI document is unreadable in a test report, and
 * the first difference is nearly always the one that explains the rest.
 */
private fun differences(committed: List<String>, actual: List<String>): String {
    val differing = (0 until maxOf(committed.size, actual.size))
        .filter { committed.getOrNull(it) != actual.getOrNull(it) }

    val shown = differing.take(DIFF_LINES).joinToString("\n") { line ->
        "  line ${line + 1}\n" +
            "    golden: ${committed.getOrNull(line) ?: "(nothing — the file ends here)"}\n" +
            "    now:    ${actual.getOrNull(line) ?: "(nothing — the file ends here)"}"
    }

    val rest = differing.size - DIFF_LINES
    return if (rest > 0) "$shown\n  and $rest more differing line${if (rest == 1) "" else "s"}" else shown
}

/** How many differing lines a failure prints before it starts counting instead. */
private const val DIFF_LINES = 3

/** What separates the two halves of a recorded exchange. Not valid HTTP, and not meant to be. */
private const val EXCHANGE_SEPARATOR = "--- response"

/**
 * A request as the text a caller would have to send.
 *
 * The header order is the description's own, not sorted: it is derived from the
 * endpoint and it is deterministic, and a multipart body's part order is part
 * of what the server reads, so reordering to make a diff quieter would hide the
 * one change worth seeing.
 */
fun RequestSpec.wireText(): String = buildString {
    append(method).append(' ').append(target).append('\n')
    headers.forEach { (name, value) -> append(name).append(": ").append(value).append('\n') }
    body?.let { append('\n').append(readable(it)) }
}

/** A response as text, minus the headers that differ between two identical runs. */
fun ResponseSpec.wireText(ignoring: Set<String> = VOLATILE_HEADERS): String = buildString {
    append(status).append('\n')
    headers
        .filterNot { (name, _) -> ignoring.any { name.equals(it, ignoreCase = true) } }
        .forEach { (name, value) -> append(name).append(": ").append(value).append('\n') }
    if (body.isNotEmpty()) append('\n').append(readable(body))
}

/**
 * JSON is re-rendered one field per line, so a changed field is one changed
 * line rather than a whole body a reviewer has to read character by character.
 * Anything that is not JSON — a form body, a rendered page, a stream — is
 * recorded exactly as it travelled.
 */
private fun readable(body: String): String {
    val trimmed = body.trim()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return body
    return runCatching { parseJson(trimmed).renderPretty() }.getOrDefault(body)
}

/**
 * A client for calls that are only ever built, never sent.
 *
 * Pinning the paths and the parameter names needs no server: `request` reads
 * the description and stops. This is the client to hand it when there is
 * nothing running — a suite of URL goldens costs one JVM and no port.
 *
 * Sending through it fails on purpose, naming the transports that do send.
 */
fun requestsOnly(codecs: Codecs): ApiClient = ApiClient(
    transport = object : Transport {
        override fun send(request: RequestSpec): ResponseSpec = throw UnsupportedOperationException(
            "This client only builds requests — `$request` was never sent, because there is nothing " +
                "here to send it to. For a call that comes back, give ApiClient one of the in-memory " +
                "transports (pelican-test-pekko, pelican-test-http4k) or HttpClientTransport.",
        )
    },
    codecs = codecs,
)
