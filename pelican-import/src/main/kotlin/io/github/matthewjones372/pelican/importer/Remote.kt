package io.github.matthewjones372.pelican.importer

import io.github.matthewjones372.pelican.JsonObj
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/**
 * References to another host: refused by default, fetched only where the build
 * file named the host, and never used without checking what came back against
 * a committed hash.
 */
internal class Remote private constructor(
    private val allowed: List<Origin>,
    private val lockfile: File?,
    /** The `endpoints` entry's name, so a refusal can spell the task that fixes it. */
    private val entry: String,
    private val updating: Boolean,
) {

    /**
     * One URL, read once. Two `$ref`s into two pointers of one document are
     * one document — re-fetching for the second would double the requests a
     * build makes and, worse, leave a build reading two different answers to
     * one URL if the far end changed between them.
     */
    private val read = LinkedHashMap<String, JsonObj>()

    /** URL -> the bytes that came back, for the hash and for the cache. */
    private val fetched = LinkedHashMap<String, ByteArray>()

    private val locked: Map<String, String> by lazy { readLockfile() }

    /** True where the build file allowed no host at all, which is the default. */
    val allowsNothing: Boolean get() = allowed.isEmpty()

    /**
     * Whether a `$ref` written in a document on disk points off the machine.
     */
    fun isRemote(ref: String) = ABSOLUTE.containsMatchIn(ref)

    // ------------------------------------------------------------ addressing

    /**
     * The document [ref] names, as a readable source or a refusal.
     */
    fun source(ref: String, base: URI?, path: JsonPath): UrlSource {
        if (allowsNothing) refuseOutright(ref, path)

        val uri = try {
            (if (base == null) URI(ref) else base.resolve(ref)).normalize()
        } catch (e: URISyntaxException) {
            throw ImportFailure("$path refers to $ref, which is not a URL: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ImportFailure("$path refers to $ref, which is not a URL: ${e.message}", e)
        }

        val scheme = uri.scheme?.lowercase()
        val host = uri.host

        if (scheme == null || host == null) {
            refuse(
                "$path refers to $ref, which names no host. A reference this build fetches is an " +
                    "absolute URL — ${allowed.first()}/something.yaml — and a relative one is read " +
                    "against the document it was written in.",
            )
        }
        if (uri.userInfo != null) {
            refuse(
                "$path refers to a URL on $host carrying a credential in it. That URL would be written " +
                    "into the lockfile and committed, so a password in it is a password in the " +
                    "repository. Publish the document somewhere the build can read without one.",
            )
        }

        val origin = Origin(scheme, host, port(scheme, uri.port))
        if (origin !in allowed) refuseOrigin(origin, scheme, ref, path)

        return UrlSource(withoutFragment(uri))
    }

    private fun refuseOrigin(origin: Origin, scheme: String, ref: String, path: JsonPath): Nothing {
        // Two different mistakes, and telling them apart is the whole value of
        // the message: an allowed host reached over the wrong scheme is one
        // word in the build file, and an unallowed host is a decision.
        val overHttp = scheme == "http" && allowed.any { it.host == origin.host && it.scheme == "https" }
        if (overHttp) {
            refuse(
                "$path refers to $ref, which is plain HTTP. This build allows ${origin.host} over https " +
                    "only. Point the reference at https, or say so on purpose with " +
                    "`allowRemote(\"http://${origin.host}\")` — which is what an internal mirror with no " +
                    "certificate needs, and what nothing else should.",
            )
        }
        if (scheme != "https" && scheme != "http") {
            refuse(
                "$path refers to $ref, which is a `$scheme:` URL. Only https is fetched — and http where " +
                    "the build file names it — because a scheme this build did not choose is a file, a " +
                    "socket or a payload dressed as a document.",
            )
        }
        refuse(
            "$path refers to $ref, and this build does not allow that host. It allows " +
                "${allowed.joinToString()}. Add `allowRemote(\"$origin\")` to the `$entry` entry if that " +
                "is a host you have read the documents of, then run `${updateTask(entry)}` to record what " +
                "they are.",
        )
    }

    /** The message this module gave before any of this existed, with the third way out added. */
    private fun refuseOutright(ref: String, path: JsonPath): Nothing = refuse(
        "$path refers to $ref, which is on another host. Remote references are not followed: " +
            "a build that fetches a URL to know what to generate cannot be reproduced. " +
            "Bundle the document first, or vendor the file it needs beside it. Where neither is " +
            "possible, name the host on purpose — `allowRemote(\"https://…\")` in the `$entry` entry — " +
            "and every URL it reaches is recorded with the hash of what came back, in a lockfile you " +
            "commit and a later build checks.",
    )

    // ------------------------------------------------------------ reading

    /** The document at [uri], checked against the lockfile unless this is the update run. */
    fun document(uri: URI, path: JsonPath): JsonObj {
        val key = uri.toString()
        read[key]?.let { return it }

        val bytes = if (updating) fetch(uri, path).also { fetched[key] = it } else verified(uri, path)
        return parsed(bytes, uri, path).also { read[key] = it }
    }

    /**
     * The bytes for [uri], proved to be the ones the lockfile records.
     */
    private fun verified(uri: URI, path: JsonPath): ByteArray {
        val key = uri.toString()
        val expected = locked[key] ?: refuse(
            "$path refers to $uri, and ${lockfile ?: "the lockfile"} does not record it. Nothing is " +
                "fetched that has not been recorded and reviewed: run `${updateTask(entry)}`, read what " +
                "it adds, and commit it.",
        )

        cached(expected)?.let { bytes ->
            val actual = hashOf(bytes)
            if (actual != expected) {
                refuse(
                    "${cacheFile(expected)} is not what it is named after: it hashes to $actual and the " +
                        "lockfile records $expected for $uri. The cached copy has been edited or " +
                        "corrupted; delete it and run `${updateTask(entry)}`.",
                )
            }
            return bytes
        }

        val bytes = fetch(uri, path)
        val actual = hashOf(bytes)
        if (actual != expected) {
            refuse(
                "$uri is not what it was when the lockfile was written.\n\n" +
                    "    at $path\n    recorded  $expected\n    fetched   $actual\n\n" +
                    "The document behind that URL has changed. Nothing is generated from it until " +
                    "somebody has looked: read what changed, then run `${updateTask(entry)} " +
                    "--accept-changes` to record the new one.",
            )
        }
        return bytes
    }

    private fun fetch(uri: URI, path: JsonPath): ByteArray = try {
        val request = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/yaml, application/json;q=0.9, */*;q=0.1")
            .GET()
            .build()

        client.send(request, HttpResponse.BodyHandlers.ofInputStream()).let { response ->
            response.body().use { body -> answer(response.statusCode(), response, body, uri, path) }
        }
    } catch (e: IOException) {
        throw ImportFailure(unreachable(uri, path, e.message ?: e.javaClass.simpleName), e)
    } catch (e: InterruptedException) {
        // Restored rather than swallowed: whoever interrupted the build is
        // still owed the flag, and a worker thread that loses it goes on
        // making requests nobody is waiting for.
        Thread.currentThread().interrupt()
        throw ImportFailure(unreachable(uri, path, "the fetch was interrupted"), e)
    }

    private fun answer(
        status: Int,
        response: HttpResponse<*>,
        body: InputStream,
        uri: URI,
        path: JsonPath,
    ): ByteArray {
        if (status in REDIRECTS) {
            refuseRedirect(uri, path, status, response.headers().firstValue("location").orElse(null))
        }
        if (status != OK) {
            refuse(
                "$path refers to $uri, and the server answered $status. A document a build reads has to " +
                    "be there every time; check the URL, and whether it needs a credential this build " +
                    "does not have.",
            )
        }

        // One byte past the limit, so "too big" and "exactly the limit" are
        // told apart by what was read rather than by a header the far end
        // wrote.
        val bytes = body.readNBytes(MAX_DOCUMENT + 1)
        if (bytes.size > MAX_DOCUMENT) {
            refuse(
                "$path refers to $uri, and it is answering with more than ${MAX_DOCUMENT / A_MEGABYTE}MB. " +
                    "That is not an OpenAPI document; whatever is at that URL, this build will not read " +
                    "it into memory to find out.",
            )
        }
        return bytes
    }

    /**
     * A redirect is refused rather than followed, even to a named host.
     */
    private fun refuseRedirect(uri: URI, path: JsonPath, status: Int, location: String?): Nothing {
        val target = location?.let { runCatching { uri.resolve(it) }.getOrNull() }
        val allowedTarget = target?.let { moved ->
            moved.scheme != null && moved.host != null &&
                Origin(moved.scheme.lowercase(), moved.host, port(moved.scheme.lowercase(), moved.port)) in allowed
        } ?: false

        refuse(
            "$path refers to $uri, and the server answered $status pointing at " +
                "${target ?: "a location it did not give"}. Redirects are not followed: a host that can " +
                "redirect is a host that can move the document out of the list this build allows. " +
                if (allowedTarget) {
                    "That host is one this build allows — write the URL it gave into the `\$ref` directly."
                } else {
                    "Write the final URL into the `\$ref`, and `allowRemote` its host if it is one you " +
                        "have read."
                },
        )
    }

    private fun unreachable(uri: URI, path: JsonPath, why: String) =
        "$path refers to $uri, and it could not be reached: $why. A build with the lockfile *and* the " +
            "cached copies beside it needs no network at all — commit ${cacheDirectory() ?: "the cache"} " +
            "and this build runs offline. Otherwise the host has to answer."

    private fun parsed(bytes: ByteArray, uri: URI, path: JsonPath): JsonObj = try {
        Document.parse(bytes.toString(Charsets.UTF_8), uri.toString())
    } catch (e: ImportFailure) {
        // The parser's own message names the line, which is the whole of what
        // a reader needs; what it cannot know is which `$ref` reached this
        // document, so that is what is added rather than a summary of it.
        throw ImportFailure("$path refers to $uri, and what came back is not a document:\n\n    ${e.message}", e)
    }

    // ------------------------------------------------------------ the lockfile

    /**
     * Records what this run fetched, and reports what changed. A moved hash is
     * the event the arrangement is for, so without [acceptChanges] it refuses
     * and prints the URLs — turning the reflex that neuters a hash check into
     * a deliberate second word on the command line.
     */
    fun update(acceptChanges: Boolean): List<String> {
        val target = lockfile ?: throw ImportFailure("No lockfile to write; set `lockfile` on the entry.")
        val now = fetched.mapValues { (_, bytes) -> hashOf(bytes) }.toSortedMap()
        val before = readLockfile()

        val changed = now.filter { (url, hash) -> before[url]?.let { it != hash } == true }
        val added = now.keys - before.keys
        val removed = before.keys - now.keys

        if (changed.isNotEmpty() && !acceptChanges) {
            throw ImportFailure(changedMessage(changed, before))
        }

        target.parentFile?.mkdirs()
        target.writeText(render(now))
        writeCache(now)

        return report(added, changed, removed, now, target)
    }

    private fun changedMessage(changed: Map<String, String>, before: Map<String, String>) = buildString {
        appendLine("${changed.size} recorded document(s) have changed since the lockfile was written:")
        appendLine()
        changed.forEach { (url, hash) ->
            appendLine("    $url")
            appendLine("      was ${before[url]}")
            appendLine("      now $hash")
        }
        appendLine()
        append(
            "This is the moment the lockfile exists for: the code this build generates is about to " +
                "change because somebody else edited a document. Read the difference, then run " +
                "`${updateTask(entry)} --accept-changes` to record it.",
        )
    }

    private fun report(
        added: Set<String>,
        changed: Map<String, String>,
        removed: Set<String>,
        now: Map<String, String>,
        target: File,
    ): List<String> = buildList {
        added.forEach { add("+ $it ${now[it]}") }
        changed.forEach { (url, hash) -> add("~ $url $hash") }
        removed.forEach { add("- $it") }
        add("Wrote $target (${now.size} reference(s))")
    }

    /**
     * The file as a diff should read it: a header, then one line per URL,
     * sorted by URL rather than by walk order — otherwise a `$ref` moving
     * between operations rewrites the file without changing a fact.
     */
    private fun render(entries: Map<String, String>) = buildString {
        appendLine("# Pelican remote reference lock. Commit this file.")
        appendLine("#")
        appendLine("# Every URL the `$entry` import fetched, and the SHA-256 of the bytes that came back.")
        appendLine("# A build checks each one and fails if it no longer matches, rather than generating")
        appendLine("# different code. Regenerate with `${updateTask(entry)}`.")
        appendLine("#")
        appendLine("# <url>  sha256:<hex>")
        entries.forEach { (url, hash) -> appendLine("$url  $hash") }
    }

    private fun readLockfile(): Map<String, String> {
        val file = lockfile?.takeIf { it.isFile } ?: return emptyMap()
        val entries = LinkedHashMap<String, String>()
        file.readLines().forEachIndexed { i, line ->
            val text = line.trim()
            if (text.isEmpty() || text.startsWith("#")) return@forEachIndexed
            val fields = text.split(Regex("\\s+"))
            if (fields.size != 2 || !fields[1].startsWith("sha256:")) {
                refuse(
                    "$file line ${i + 1} is not a lockfile entry: `<url>  sha256:<hex>` is the shape, and " +
                        "the line says `$text`. Regenerate it with `${updateTask(entry)}`.",
                )
            }
            if (entries.put(fields[0], fields[1]) != null) {
                refuse(
                    "$file records ${fields[0]} twice, with two different hashes it cannot both mean. " +
                        "Regenerate it with `${updateTask(entry)}`.",
                )
            }
        }
        return entries
    }

    // ------------------------------------------------------------ the cache

    /**
     * The fetched documents, beside the lockfile, named by their own hash.
     */
    private fun cacheDirectory(): File? = lockfile?.let { File(it.parentFile, it.name + ".d") }

    private fun cacheFile(hash: String) = File(cacheDirectory(), hash.removePrefix("sha256:") + ".yaml")

    private fun cached(hash: String): ByteArray? = cacheFile(hash).takeIf { it.isFile }?.readBytes()

    private fun writeCache(entries: Map<String, String>) {
        val directory = cacheDirectory() ?: return
        directory.mkdirs()
        entries.forEach { (url, hash) -> fetched[url]?.let { cacheFile(hash).writeBytes(it) } }

        val keep = entries.values.map { cacheFile(it).name }.toSet()
        directory.listFiles().orEmpty()
            .filter { it.isFile && CACHED.matches(it.name) && it.name !in keep }
            .forEach { it.delete() }
    }

    // ------------------------------------------------------------

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build()
    }

    companion object {
        private const val OK = 200
        private val REDIRECTS = 300..399
        private const val A_MEGABYTE = 1024 * 1024

        /** The largest published OpenAPI documents are single-digit megabytes. */
        private const val MAX_DOCUMENT = 16 * A_MEGABYTE

        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val CACHED = Regex("[0-9a-f]{64}\\.yaml")
        private val ABSOLUTE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://|^//")

        /** Reading a document: the lockfile is the authority and nothing else is fetched. */
        fun forImport(options: ImportOptions): Remote {
            val allowed = options.allowRemote.map { origin(it) }
            if (allowed.isNotEmpty() && options.lockfile == null) {
                refuse(
                    "`allowRemote` names ${allowed.joinToString()} and no lockfile was given. The " +
                        "lockfile is what makes a build that fetches reproducible, so there is no mode " +
                        "that allows a host without one: set `lockfile` on the `${options.name}` entry.",
                )
            }
            return Remote(allowed, options.lockfile, options.name, updating = false)
        }

        /** Writing the lockfile: what the far end says now is the answer, by definition. */
        fun forUpdate(options: ImportOptions): Remote {
            if (options.allowRemote.isEmpty()) {
                refuse(
                    "The `${options.name}` entry allows no remote references, so there is nothing to " +
                        "lock. `allowRemote(\"https://…\")` is what says a host may be fetched from; " +
                        "until one is named, a `\$ref` to another host fails the import.",
                )
            }
            return Remote(options.allowRemote.map { origin(it) }, options.lockfile, options.name, updating = true)
        }

        /**
         * One entry of the allowlist, as an origin — scheme, host, port —
         * rather than a URL prefix: `https://good.example` is a prefix of
         * `https://good.example.evil.test`, and three fields compared for
         * equality have no such second reading.
         */
        private fun origin(written: String): Origin {
            val hasScheme = written.contains("://")
            val uri = runCatching { URI(if (hasScheme) written else "https://$written") }.getOrNull()
                ?: refuse(unreadable(written))
            val scheme = uri.scheme?.lowercase() ?: refuse(unreadable(written))
            val host = uri.host ?: refuse(unreadable(written))

            if (scheme != "https" && scheme != "http") {
                refuse(
                    "`allowRemote(\"$written\")` names the scheme `$scheme`. A build fetches over https, " +
                        "or over http where it says so on purpose, and over nothing else.",
                )
            }
            if (uri.path.orEmpty().trim('/').isNotEmpty() || uri.query != null || uri.fragment != null) {
                refuse(
                    "`allowRemote(\"$written\")` names a URL. What is allowed is a host — " +
                        "`$scheme://$host` — because a path is not a boundary the far end respects: it " +
                        "serves whatever it likes under one.",
                )
            }
            return Origin(scheme, host, port(scheme, uri.port))
        }

        private fun unreadable(written: String) =
            "`allowRemote(\"$written\")` is not a host. It is `example.com`, or `https://example.com`, " +
                "or `http://mirror.internal:8080` where plain HTTP is meant on purpose."

        private fun port(scheme: String, declared: Int) =
            if (declared != -1) declared else if (scheme == "https") HTTPS else HTTP

        private const val HTTPS = 443
        private const val HTTP = 80

        private fun withoutFragment(uri: URI): URI =
            if (uri.fragment == null) uri else URI(uri.toString().substringBefore('#'))

        /**
         * The bytes that arrived, and nothing else — not the parsed tree
         * re-rendered. A hash over a normalised form records what this parser
         * understood rather than what the far end sent, so two byte streams
         * would share a hash and a parser change would find the lockfile
         * already calling them one document. It also lets the cached copy be
         * checked without parsing.
         */
        private fun hashOf(bytes: ByteArray): String =
            "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        /** The task that rewrites the lockfile, named as the plugin registers it. */
        fun updateTask(entry: String) = "update${entry.replaceFirstChar { it.uppercase() }}EndpointsLock"
    }
}

/** A scheme, a host and a port: what one line of the allowlist means. */
internal data class Origin(val scheme: String, val host: String, val port: Int) {
    override fun toString(): String {
        val implied = if (scheme == "https") 443 else 80
        return if (port == implied) "$scheme://$host" else "$scheme://$host:$port"
    }
}

/**
 * Refusing to fetch, said the way the rest of this module refuses: a message
 * written for whoever has to fix it, and nothing else.
 */
private fun refuse(message: String): Nothing = throw ImportFailure(message)
