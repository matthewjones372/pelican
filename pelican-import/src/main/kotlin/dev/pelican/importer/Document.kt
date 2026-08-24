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

/**
 * Reading a document off disk, as one self-contained tree.
 *
 * YAML 1.2 is a superset of JSON, so the same parser reads both and the file
 * extension decides nothing. What comes back is core's [JsonValue] — the type
 * `pelican-openapi` writes documents out of — so the two directions are
 * reading and writing the same shape rather than two models of it.
 *
 * References to other files are resolved here rather than left to whatever
 * reads the tree next, and references to other *hosts* are refused. A build
 * that fetches a URL to know what to compile is a build that compiles
 * something different depending on the network, and the failure mode of that
 * is a generated client nobody can reproduce.
 */
internal object Document {

    /** Reads [file] and everything it references, as one tree with only local refs left. */
    fun read(file: File): JsonObj {
        val root = parse(file)
        val bundle = Bundle(root)
        val bundled = bundle.walk(root, null, file.canonicalFile.parentFile, JsonPath.root) as JsonObj
        return bundle.finish(bundled)
    }

    fun parse(file: File): JsonObj {
        if (!file.isFile) throw ImportFailure("No such file: $file")
        val loaded = try {
            Load(settings).loadFromString(file.readText())
        } catch (e: YamlEngineException) {
            // snakeyaml throws its own hierarchy for a malformed document, and
            // the message it carries names the line — which is the whole of
            // what a reader needs, so it is passed on rather than summarised.
            throw ImportFailure("$file is not valid YAML or JSON: ${e.message}", e)
        }
        return loaded.toJson() as? JsonObj
            ?: throw ImportFailure("$file does not hold an object at its root, so it is not an OpenAPI document")
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
 * The `$ref`s that point out of this file, resolved into it.
 *
 * A reference to another file's schema is hoisted into `components/schemas`
 * under its own name, because a type that had a name in the file it came from
 * should keep it — the generated Kotlin is named after that, and a spec split
 * across files would otherwise generate `OrderShipping`-style names invented
 * from where each type happened to be used. Everything else is inlined where
 * it stood.
 */
private class Bundle(root: JsonObj) {
    /** (file, pointer) -> the name it was hoisted under, so one type is hoisted once. */
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
     * [source] is the file this value was read from, or null for the document
     * itself. It is what makes a local `#/...` inside a pulled-in file mean
     * what it said there: after bundling, that pointer would otherwise be read
     * against the root document, where it names either nothing or — worse —
     * something else of the same name.
     */
    fun walk(value: JsonValue, source: File?, dir: File, path: JsonPath): JsonValue = when (value) {
        is JsonArr -> JsonArr(value.items.mapIndexed { i, item -> walk(item, source, dir, path / i) })

        is JsonObj -> {
            val ref = (value["\$ref"] as? JsonStr)?.value
            if (ref == null) {
                JsonObj(value.fields.mapValues { (key, field) -> walk(field, source, dir, path / key) })
            } else {
                resolve(ref, source, dir, path)
            }
        }

        else -> value
    }

    private fun resolve(ref: String, source: File?, dir: File, path: JsonPath): JsonValue {
        if (ref.startsWith("#")) {
            if (source == null) return JsonObj(mapOf("\$ref" to JsonStr(ref)))
            // Written as local, and local to a file that is not this one.
            return resolve("${source.name}$ref", null, source.parentFile, path)
        }

        if (remote.containsMatchIn(ref)) {
            throw ImportFailure(
                "$path refers to $ref, which is on another host. Remote references are not followed: " +
                    "a build that fetches a URL to know what to generate cannot be reproduced. " +
                    "Bundle the document first, or vendor the file it needs beside it.",
            )
        }

        val (fileName, pointer) = ref.split("#", limit = 2).let { it[0] to it.getOrNull(1).orEmpty() }
        val target = File(dir, fileName).canonicalFile
        val key = "$target#$pointer"
        hoisted[key]?.let { return JsonObj(mapOf("\$ref" to JsonStr("#/components/schemas/$it"))) }

        if (key in visiting) {
            throw ImportFailure("$path refers to $ref, which refers back to itself through another file")
        }
        visiting += key

        val document = Document.parse(target)
        val subtree = pointerInto(document, pointer, ref, path)
        val name = hoistedName(pointer, target)

        return if (name == null) {
            // Not a named schema anywhere: inlined where it stood, which is
            // what a reader of the merged document would expect to see.
            walk(subtree, target, target.parentFile, path).also { visiting -= key }
        } else {
            val unique = unique(name)
            // Registered before recursing, so a schema that refers to itself
            // through a file boundary lands on the name already reserved.
            hoisted[key] = unique
            added[unique] = walk(subtree, target, target.parentFile, path)
            visiting -= key
            JsonObj(mapOf("\$ref" to JsonStr("#/components/schemas/$unique")))
        }
    }

    /**
     * What to call a schema pulled in from another file: the name it had
     * there, or the file's own name when the whole file is one schema. Null
     * for a reference to something that is not a schema — a shared parameter
     * or response — which is inlined instead.
     */
    private fun hoistedName(pointer: String, target: File): String? {
        val steps = pointer.trim('/').split('/').filter { it.isNotEmpty() }
        val section = steps.getOrNull(steps.size - 2)
        val underSchemas = section == "schemas" || section == "definitions"
        return when {
            underSchemas -> typeName(unescape(steps.last()))
            steps.isEmpty() -> typeName(target.name.substringBeforeLast('.'))
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

    private val remote = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://|^//")
}
