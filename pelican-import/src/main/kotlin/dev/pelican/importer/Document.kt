package dev.pelican.importer

import dev.pelican.JsonArr
import dev.pelican.JsonBool
import dev.pelican.JsonNull
import dev.pelican.JsonNum
import dev.pelican.JsonObj
import dev.pelican.JsonStr
import dev.pelican.JsonValue
import dev.pelican.codegen.typeName
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import java.io.File
import java.net.URI

/**
 * Reading a document off disk, as one self-contained tree.
 *
 * YAML 1.2 is a superset of JSON, so the same parser reads both and the file
 * extension decides nothing. What comes back is core's [JsonValue] — the type
 * `pelican-openapi` writes documents out of — so the two directions are
 * reading and writing the same shape rather than two models of it.
 *
 * References to other files are resolved here rather than left to whatever
 * reads the tree next, and references to other *hosts* are refused unless the
 * build file named the host. A build that fetches a URL to know what to
 * compile is a build that compiles something different depending on the
 * network, and the failure mode of that is a generated client nobody can
 * reproduce — so where fetching is allowed at all, [Remote] is what makes the
 * fetched half a fixed input again.
 */
internal object Document {

    /** Reads [file] and everything it references, as one tree with only local refs left. */
    fun read(file: File, remote: Remote): JsonObj {
        val root = parse(file)
        val bundle = Bundle(root, FileSource(file.canonicalFile), remote)
        val bundled = bundle.walk(root, null, JsonPath.root) as JsonObj
        return bundle.finish(bundled)
    }

    fun parse(file: File): JsonObj {
        if (!file.isFile) throw ImportFailure("No such file: $file")
        return parse(file.readText(), file.toString())
    }

    /**
     * The same parse for text that never was a file.
     *
     * [label] is what a failure calls it — a path for a file, a URL for a
     * fetched document. One parser for both, because a document that arrived
     * over HTTP is read by exactly the same rules as one on disk: a second
     * reading of YAML would be a second place for duplicate keys to become
     * last-one-wins.
     */
    fun parse(text: String, label: String): JsonObj {
        val loaded = try {
            Load(settings).loadFromString(text)
        } catch (e: YamlEngineException) {
            // snakeyaml throws its own hierarchy for a malformed document, and
            // the message it carries names the line — which is the whole of
            // what a reader needs, so it is passed on rather than summarised.
            throw ImportFailure("$label is not valid YAML or JSON: ${e.message}", e)
        }
        return loaded.toJson() as? JsonObj
            ?: throw ImportFailure("$label does not hold an object at its root, so it is not an OpenAPI document")
    }

    /**
     * Duplicate keys are an error rather than a last-one-wins: a document with
     * two `responses` blocks under one operation has already lost half of
     * itself, and generating from the half that survived would be worse than
     * saying so.
     */
    private val settings: LoadSettings = LoadSettings.builder()
        .setAllowDuplicateKeys(false)
        .setLabel("OpenAPI document")
        .build()
}

/** snakeyaml's plain Java values as core's tree. */
private fun Any?.toJson(): JsonValue = when (this) {
    null -> JsonNull

    is Map<*, *> -> JsonObj(entries.associate { (k, v) -> k.toString() to v.toJson() })

    is List<*> -> JsonArr(map { it.toJson() })

    is String -> JsonStr(this)

    is Boolean -> JsonBool(this)

    is Number -> JsonNum(this)

    // A YAML date, a binary blob, a set: legal YAML, and nothing OpenAPI can
    // mean by it. Kept as its own text rather than dropped, so whatever reads
    // the tree next reports where it was rather than that it was missing.
    else -> JsonStr(toString())
}

/**
 * Where in the document we are, for a failure message. A path costs one
 * allocation per level and buys "components.schemas.Order.properties.total"
 * instead of "somewhere in your spec".
 */
internal class JsonPath private constructor(private val parent: JsonPath?, private val step: String) {
    operator fun div(next: String) = JsonPath(this, next)
    operator fun div(next: Int) = JsonPath(this, "[$next]")

    override fun toString(): String {
        val here = parent?.toString().orEmpty()
        return when {
            here.isEmpty() -> step
            step.startsWith('[') -> here + step
            else -> "$here.$step"
        }
    }

    companion object {
        val root = JsonPath(null, "")
    }
}

/**
 * Where a value was read from, and what a `$ref` written inside it means.
 *
 * A file on disk and a fetched URL answer the same two questions — what does
 * a reference written here resolve to, and what does a failure call this — so
 * they answer them behind one type. Keeping them apart would have meant a
 * second walk of the tree for the fetched half, and the two walks would
 * eventually come to disagree about how a `#/...` inside a pulled-in document
 * is read: the bug this type exists to make impossible.
 */
internal sealed class Source {

    /** Identity: what "hoisted once" and "already visiting" are keyed by, and what a message says. */
    abstract val id: String

    /** The last segment without its extension, for a reference to a whole document. */
    abstract val stem: String

    /** What [ref] names, read from here — or a refusal saying why it is not read. */
    abstract fun sibling(ref: String, path: JsonPath, remote: Remote): Source

    abstract fun read(remote: Remote, path: JsonPath): JsonObj

    override fun toString(): String = id
}

internal class FileSource(private val file: File) : Source() {
    override val id: String = file.path
    override val stem: String = file.name.substringBeforeLast('.')

    override fun sibling(ref: String, path: JsonPath, remote: Remote): Source =
        // A remote reference written in a local document has to be absolute:
        // there is no host for a relative one to be relative to.
        if (remote.isRemote(ref)) {
            remote.source(ref, null, path)
        } else {
            FileSource(File(file.parentFile, ref).canonicalFile)
        }

    override fun read(remote: Remote, path: JsonPath): JsonObj = Document.parse(file)
}

internal class UrlSource(private val uri: URI) : Source() {
    override val id: String = uri.toString()
    override val stem: String = uri.path.orEmpty().substringAfterLast('/').substringBeforeLast('.')

    // Relative and absolute alike: `./common.yaml` beside a fetched document
    // is another document on that host, and it is checked against the
    // allowlist exactly as the first one was. A fetched document naming a
    // second host is followed only where the build file named that host too.
    override fun sibling(ref: String, path: JsonPath, remote: Remote): Source = remote.source(ref, uri, path)

    override fun read(remote: Remote, path: JsonPath): JsonObj = remote.document(uri, path)
}

/**
 * The `$ref`s that point out of this file, resolved into it.
 *
 * A reference to another file's schema is hoisted into `components/schemas`
 * under its own name, because a type that had a name in the file it came from
 * should keep it — the generated Kotlin is named after that, and a spec split
 * across files would otherwise generate `OrderShipping`-style names invented
 * from where each type happened to be used. Everything else is inlined where
 * it stood.
 */
private class Bundle(root: JsonObj, private val rootSource: Source, private val remote: Remote) {
    /** (document, pointer) -> the name it was hoisted under, so one type is hoisted once. */
    private val hoisted = LinkedHashMap<String, String>()
    private val added = LinkedHashMap<String, JsonValue>()
    private val taken = ((root["components"] as? JsonObj)?.get("schemas") as? JsonObj)
        ?.fields?.keys?.toMutableSet() ?: mutableSetOf()

    /** In progress, so a pair of files referring to each other terminates. */
    private val visiting = mutableSetOf<String>()

    fun finish(bundled: JsonObj): JsonObj {
        if (added.isEmpty()) return bundled
        val components = bundled["components"] as? JsonObj ?: JsonObj(emptyMap())
        val schemas = components["schemas"] as? JsonObj ?: JsonObj(emptyMap())
        return JsonObj(
            bundled.fields + mapOf(
                "components" to JsonObj(
                    components.fields + mapOf("schemas" to JsonObj(schemas.fields + added)),
                ),
            ),
        )
    }

    /**
     * [source] is where this value was read from, or null for the document
     * itself. It is what makes a local `#/...` inside a pulled-in document
     * mean what it said there: after bundling, that pointer would otherwise be
     * read against the root document, where it names either nothing or —
     * worse — something else of the same name.
     */
    fun walk(value: JsonValue, source: Source?, path: JsonPath): JsonValue = when (value) {
        is JsonArr -> JsonArr(value.items.mapIndexed { i, item -> walk(item, source, path / i) })

        is JsonObj -> {
            val ref = (value["\$ref"] as? JsonStr)?.value
            if (ref == null) {
                JsonObj(value.fields.mapValues { (key, field) -> walk(field, source, path / key) })
            } else {
                resolve(ref, source, path)
            }
        }

        else -> value
    }

    private fun resolve(ref: String, source: Source?, path: JsonPath): JsonValue {
        if (ref.startsWith("#")) {
            // Written as local, and local to a document that is not this one.
            if (source == null) return JsonObj(mapOf("\$ref" to JsonStr(ref)))
            return pull(source, ref.removePrefix("#"), ref, path)
        }

        val (where, pointer) = ref.split("#", limit = 2).let { it[0] to it.getOrNull(1).orEmpty() }
        return pull((source ?: rootSource).sibling(where, path, remote), pointer, ref, path)
    }

    private fun pull(target: Source, pointer: String, ref: String, path: JsonPath): JsonValue {
        val key = "${target.id}#$pointer"
        hoisted[key]?.let { return JsonObj(mapOf("\$ref" to JsonStr("#/components/schemas/$it"))) }

        if (key in visiting) {
            throw ImportFailure("$path refers to $ref, which refers back to itself through another document")
        }
        visiting += key

        val document = target.read(remote, path)
        val subtree = pointerInto(document, pointer, ref, path)
        val name = hoistedName(pointer, target)

        return if (name == null) {
            // Not a named schema anywhere: inlined where it stood, which is
            // what a reader of the merged document would expect to see.
            walk(subtree, target, path).also { visiting -= key }
        } else {
            val unique = unique(name)
            // Registered before recursing, so a schema that refers to itself
            // across a document boundary lands on the name already reserved.
            hoisted[key] = unique
            added[unique] = walk(subtree, target, path)
            visiting -= key
            JsonObj(mapOf("\$ref" to JsonStr("#/components/schemas/$unique")))
        }
    }

    /**
     * What to call a schema pulled in from another document: the name it had
     * there, or the document's own name when the whole of it is one schema.
     * Null for a reference to something that is not a schema — a shared
     * parameter or response — which is inlined instead.
     */
    private fun hoistedName(pointer: String, target: Source): String? {
        val steps = pointer.trim('/').split('/').filter { it.isNotEmpty() }
        val section = steps.getOrNull(steps.size - 2)
        val underSchemas = section == "schemas" || section == "definitions"
        return when {
            underSchemas -> typeName(unescape(steps.last()))
            steps.isEmpty() -> typeName(target.stem)
            else -> null
        }
    }

    private fun unique(name: String): String {
        var candidate = name
        var n = 2
        while (!taken.add(candidate)) {
            candidate = "$name$n"
            n++
        }
        return candidate
    }

    private fun pointerInto(document: JsonObj, pointer: String, ref: String, path: JsonPath): JsonValue {
        var here: JsonValue = document
        pointer.trim('/').split('/').filter { it.isNotEmpty() }.forEach { rawStep ->
            val step = unescape(rawStep)
            here = when (val current = here) {
                is JsonObj -> current[step]
                is JsonArr -> step.toIntOrNull()?.let { current.items.getOrNull(it) }
                else -> null
            } ?: throw ImportFailure("$path refers to $ref, and there is nothing at that pointer")
        }
        return here
    }

    /** RFC 6901: `~1` is a slash and `~0` a tilde, in that order. */
    private fun unescape(step: String) = step.replace("~1", "/").replace("~0", "~")
}
