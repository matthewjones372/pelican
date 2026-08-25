package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.ApiSpec
import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonBool
import io.github.matthewjones372.pelican.JsonNum
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue

/*
 * What a change to the descriptions does to the people already calling the
 * service.
 *
 * A textual diff of two documents says a line moved. It cannot say whether the
 * line that moved was a new optional query parameter, which nobody notices, or
 * a new *required* one, which turns every existing caller into a 400 — and a
 * check that cannot tell those apart teaches people to accept its output
 * without reading it, which is worse than not checking at all.
 *
 * So the two documents are compared as documents, and every difference is
 * classified from the caller's side of the wire.
 */

/** What a change does to a caller written against the published document. */
enum class Compatibility {
    /** A caller that worked stops working: it is refused, or it stops being told something it read. */
    BREAKING,

    /** A caller that worked keeps working, and something new is there for the next one. */
    COMPATIBLE,

    /** Prose. A summary, a description — nothing a caller executes. */
    COSMETIC,
}

/**
 * One difference between two documents, and what it costs.
 *
 * [what] and [consequence] are two fields rather than one sentence because
 * they are read differently: the claim is scanned, and the consequence is what
 * somebody reads once they have stopped to look. A report can lay them out;
 * a log line puts them back together, which is what [toString] does.
 */
data class ApiChange(
    val compatibility: Compatibility,
    /** The operation it happened in, as a caller would name it: `POST /users/{userId}/orders`. */
    val where: String,
    /** What changed, in the terms the caller has to care about. */
    val what: String,
    /** What it does to them — the sentence that decides whether this can ship. */
    val consequence: String = "",
) {
    override fun toString(): String = if (consequence.isEmpty()) "$where — $what" else "$where — $what; $consequence"
}

/**
 * Every difference between the document that was published and the one the
 * descriptions produce now.
 *
 * ```
 * apiChanges(published, ordersSpec().openApi()).filter { it.compatibility == Compatibility.BREAKING }
 * ```
 *
 * Both sides are documents rather than [ApiSpec]s, because the published one is
 * nearly always a file: the whole point is that one side is older than the
 * source tree.
 */
fun apiChanges(published: JsonObj, proposed: JsonObj): List<ApiChange> = Comparison(published, proposed).run()

/** The same comparison for a caller holding the spec rather than its document. */
fun ApiSpec.changesFrom(published: JsonObj): List<ApiChange> = apiChanges(published, openApi())

/**
 * Which side of the wire a payload is on, from the *other party's* point of
 * view — the only point of view that decides whether a change breaks anything.
 *
 * A request is something the caller sends, so it may be loosened and not
 * tightened. A response is something the caller reads, so it may be widened
 * with new fields and not narrowed. A webhook is the same rules with the arrows
 * reversed: its request is what this service sends to a subscriber, so the
 * subscriber is the one reading it, and a field they read disappearing is what
 * breaks them.
 */
private enum class Direction { CALLER_SENDS, CALLER_RECEIVES }

private class Comparison(private val published: JsonObj, private val proposed: JsonObj) {

    fun run(): List<ApiChange> = (
        operations("paths", Direction.CALLER_SENDS) +
            operations("webhooks", Direction.CALLER_RECEIVES)
        ).sortedBy { it.compatibility.ordinal }

    // -------------------------------------------------------------- operations

    /**
     * `paths` and `webhooks` are the same shape — a key, a method, an operation
     * — so one walk serves both, and they differ only in which way the payloads
     * travel.
     */
    private fun operations(section: String, requests: Direction): List<ApiChange> {
        val old = published[section].obj()?.fields.orEmpty()
        val new = proposed[section].obj()?.fields.orEmpty()

        return (old.keys + new.keys).flatMap { key ->
            val oldMethods = old[key].obj()?.fields.orEmpty()
            val newMethods = new[key].obj()?.fields.orEmpty()

            (oldMethods.keys + newMethods.keys).flatMap { method ->
                pathItem("${method.uppercase()} $key", oldMethods[method].obj(), newMethods[method].obj(), requests)
            }
        }
    }

    private fun pathItem(where: String, before: JsonObj?, after: JsonObj?, requests: Direction): List<ApiChange> =
        when {
            before == null && after == null -> emptyList()

            before == null -> one(Compatibility.COMPATIBLE, where, "a new operation")

            after == null -> one(
                Compatibility.BREAKING,
                where,
                "the operation is gone",
                "every caller still holding it gets a 404",
            )

            else -> identity(where, before, after) +
                security(where, before, after) +
                parameters(where, before, after, requests) +
                requestBody(where, before, after, requests) +
                responses(where, before, after, flip(requests))
        }

    /**
     * The `operationId` is not prose: it is the name of the method on every
     * generated client, so changing it renames a function in somebody else's
     * source tree.
     */
    private fun identity(where: String, before: JsonObj, after: JsonObj): List<ApiChange> {
        val old = before["operationId"].str()
        val new = after["operationId"].str()

        val renamed = if (old == new) {
            emptyList()
        } else {
            one(
                Compatibility.BREAKING,
                where,
                "the operationId is `$new` where it was `$old`",
                "every generated client renames the method with it",
            )
        }

        val prose = listOf("summary", "description")
            .filter { before[it].str() != after[it].str() }
            .flatMap { one(Compatibility.COSMETIC, where, "the $it changed") }

        val deprecated = if (!before["deprecated"].bool() && after["deprecated"].bool()) {
            one(Compatibility.COMPATIBLE, where, "deprecated; it still answers")
        } else {
            emptyList()
        }

        return renamed + prose + deprecated
    }

    /** A credential the operation did not ask for before is a 401 for everyone not sending it. */
    private fun security(where: String, before: JsonObj, after: JsonObj): List<ApiChange> {
        val old = schemeNames(before["security"])
        val new = schemeNames(after["security"])

        return (new - old).flatMap {
            one(
                Compatibility.BREAKING,
                where,
                "it requires `$it` now",
                "callers not sending that credential are refused",
            )
        } + (old - new).flatMap {
            one(Compatibility.COMPATIBLE, where, "it no longer requires `$it`")
        }
    }

    private fun schemeNames(security: JsonValue?): Set<String> =
        security.arr().flatMap { it.obj()?.fields?.keys.orEmpty() }.toSet()

    // -------------------------------------------------------------- parameters

    private fun parameters(where: String, before: JsonObj, after: JsonObj, direction: Direction): List<ApiChange> {
        val old = parametersByName(before)
        val new = parametersByName(after)

        return (old.keys + new.keys).flatMap { name ->
            val was = old[name]
            val now = new[name]

            when {
                was == null -> added(where, "the $name parameter", now?.get("required").bool(), direction)

                now == null -> one(
                    Compatibility.BREAKING,
                    where,
                    "the $name parameter is gone",
                    "a caller sending it is describing a request nothing reads",
                )

                else -> requiredness(
                    where,
                    "the $name parameter",
                    was["required"].bool(),
                    now["required"].bool(),
                    direction,
                ) + schema(where, "the $name parameter", was["schema"], now["schema"], direction)
            }
        }
    }

    /** Keyed by name *and* location: `limit` in the query and `limit` in a header are two parameters. */
    private fun parametersByName(operation: JsonObj): Map<String, JsonObj> =
        operation["parameters"].arr().mapNotNull { it.obj() }.associateBy {
            "`${it["name"].str()}` ${it["in"].str()}"
        }

    // ------------------------------------------------------------ request body

    private fun requestBody(where: String, before: JsonObj, after: JsonObj, direction: Direction): List<ApiChange> {
        val was = before["requestBody"].obj()
        val now = after["requestBody"].obj()

        return when {
            was == null && now == null -> emptyList()

            was == null -> added(where, "a request body", now?.get("required").bool(), direction)

            now == null -> one(Compatibility.COMPATIBLE, where, "the request body is ignored now")

            else -> requiredness(
                where,
                "the request body",
                was["required"].bool(),
                now["required"].bool(),
                direction,
            ) + content(where, "the request body", was, now, direction)
        }
    }

    // --------------------------------------------------------------- responses

    private fun responses(where: String, before: JsonObj, after: JsonObj, direction: Direction): List<ApiChange> {
        val old = before["responses"].obj()?.fields.orEmpty()
        val new = after["responses"].obj()?.fields.orEmpty()

        return (old.keys + new.keys).flatMap { status ->
            val was = old[status].obj()
            val now = new[status].obj()

            when {
                was == null -> one(Compatibility.COMPATIBLE, where, "a new $status response")

                now == null -> one(
                    Compatibility.BREAKING,
                    where,
                    "the $status response is no longer declared",
                    "a caller handling it is handling something this service says it never sends",
                )

                else -> responseHeaders(where, status, was, now) +
                    content(where, "the $status response", was, now, direction)
            }
        }
    }

    private fun responseHeaders(where: String, status: String, before: JsonObj, after: JsonObj): List<ApiChange> {
        val old = before["headers"].obj()?.fields.orEmpty()
        val new = after["headers"].obj()?.fields.orEmpty()

        return (old.keys + new.keys).flatMap { name ->
            val was = old[name].obj()
            val now = new[name].obj()

            when {
                was == null -> one(Compatibility.COMPATIBLE, where, "the $status response carries `$name` now")

                now == null -> one(
                    Compatibility.BREAKING,
                    where,
                    "the $status response no longer carries `$name`",
                    "a caller reading that header gets nothing",
                )

                was["required"].bool() && !now["required"].bool() -> one(
                    Compatibility.BREAKING,
                    where,
                    "`$name` on the $status response is optional now",
                    "a caller reading that header may find it absent",
                )

                else -> emptyList()
            }
        }
    }

    // ----------------------------------------------------------------- content

    /** A media type is part of what a caller had to be able to speak, in either direction. */
    private fun content(
        where: String,
        what: String,
        before: JsonObj,
        after: JsonObj,
        direction: Direction,
    ): List<ApiChange> {
        val old = before["content"].obj()?.fields.orEmpty()
        val new = after["content"].obj()?.fields.orEmpty()

        return (old.keys + new.keys).flatMap { mediaType ->
            val was = old[mediaType].obj()
            val now = new[mediaType].obj()

            when {
                was == null -> one(Compatibility.COMPATIBLE, where, "$what speaks $mediaType as well now")
                now == null -> one(Compatibility.BREAKING, where, "$what no longer speaks $mediaType")
                else -> schema(where, "$what ($mediaType)", was["schema"], now["schema"], direction)
            }
        }
    }

    // ----------------------------------------------------------------- schemas

    /**
     * Payload schemas, compared through their `$ref`s and down into their
     * properties.
     *
     * `seen` is the cycle guard, carried as a value rather than accumulated: a
     * schema that references itself — a tree node whose children are the same
     * type — is compared once and then trusted, since the second visit would
     * compare exactly what the first one did.
     */
    private fun schema(
        where: String,
        what: String,
        before: JsonValue?,
        after: JsonValue?,
        direction: Direction,
        seen: Set<String> = emptySet(),
    ): List<ApiChange> {
        val ref = before.obj()?.get("\$ref").str()
        if (ref != null && ref == after.obj()?.get("\$ref").str() && ref in seen) return emptyList()

        val was = resolve(before, published) ?: return emptyList()
        val now = resolve(after, proposed) ?: return emptyList()
        val visited = if (ref == null) seen else seen + ref

        return types(where, what, was, now, direction) +
            enums(where, what, was, now, direction) +
            constraints(where, what, was, now, direction) +
            properties(where, what, was, now, direction, visited) +
            elements(where, what, was, now, direction, visited)
    }

    private fun elements(
        where: String,
        what: String,
        before: JsonObj,
        after: JsonObj,
        direction: Direction,
        seen: Set<String>,
    ): List<ApiChange> =
        if (before["items"] == null && after["items"] == null) {
            emptyList()
        } else {
            schema(where, "$what's elements", before["items"], after["items"], direction, seen)
        }

    /** `$ref` is followed in the document it came from: the two sides have components of their own. */
    private fun resolve(schema: JsonValue?, document: JsonObj): JsonObj? {
        val obj = schema.obj() ?: return null
        val ref = obj["\$ref"].str() ?: return obj
        val name = ref.removePrefix("#/components/schemas/")
        return document["components"].obj()?.get("schemas").obj()?.get(name).obj() ?: obj
    }

    /**
     * 3.1 spells nullability as a type *set*, so widening and narrowing are the
     * same comparison as everything else here: `["string", "null"]` where a
     * caller was promised `string` is a null they were never told to expect.
     */
    private fun types(
        where: String,
        what: String,
        before: JsonObj,
        after: JsonObj,
        direction: Direction,
    ): List<ApiChange> {
        val old = typeNames(before)
        val new = typeNames(after)
        if (old == new) return emptyList()

        val wider = (new - old).takeIf { it.isNotEmpty() }?.let {
            one(widening(direction), where, "$what ${verb(direction)} ${it.joinToString()} now as well")
        }.orEmpty()

        val narrower = (old - new).takeIf { it.isNotEmpty() }?.let {
            one(narrowing(direction), where, "$what no longer ${verb(direction)} ${it.joinToString()}")
        }.orEmpty()

        return wider + narrower
    }

    private fun typeNames(schema: JsonObj): Set<String> = when (val type = schema["type"]) {
        is JsonStr -> setOf(type.value)
        is JsonArr -> type.items.mapNotNull { it.str() }.toSet()
        else -> emptySet()
    }

    /**
     * An enum is a closed set on both sides of the wire, and closing it further
     * or opening it wider breaks whichever end was told the old set: a value
     * removed is a request that stops being accepted, and a value added is a
     * response a generated client's `when` has never heard of.
     */
    private fun enums(
        where: String,
        what: String,
        before: JsonObj,
        after: JsonObj,
        direction: Direction,
    ): List<ApiChange> {
        val old = before["enum"].arr().mapNotNull { it.str() }.toSet()
        val new = after["enum"].arr().mapNotNull { it.str() }.toSet()

        return (new - old).flatMap { one(widening(direction), where, "$what has a new value `$it`") } +
            (old - new).flatMap { one(narrowing(direction), where, "$what no longer allows `$it`") }
    }

    /** What a value has to look like. Tightening one refuses payloads that were being accepted. */
    private fun constraints(
        where: String,
        what: String,
        before: JsonObj,
        after: JsonObj,
        direction: Direction,
    ): List<ApiChange> {
        if (direction == Direction.CALLER_RECEIVES) return emptyList()

        val bounds = TIGHTENED_BY_RISING.flatMap { bound(where, what, it, before, after) { o, n -> n > o } } +
            TIGHTENED_BY_FALLING.flatMap { bound(where, what, it, before, after) { o, n -> n < o } }

        val pattern = after["pattern"].str()
        val patterned = if (pattern != null && pattern != before["pattern"].str()) {
            one(Compatibility.BREAKING, where, "$what must match `$pattern` now")
        } else {
            emptyList()
        }

        return bounds + patterned
    }

    private fun bound(
        where: String,
        what: String,
        key: String,
        before: JsonObj,
        after: JsonObj,
        tighter: (Double, Double) -> Boolean,
    ): List<ApiChange> {
        val old = before[key].num() ?: return emptyList()
        val new = after[key].num() ?: return emptyList()
        if (old == new) return emptyList()

        val compatibility = if (tighter(old, new)) Compatibility.BREAKING else Compatibility.COMPATIBLE
        return one(compatibility, where, "$what has $key ${plain(new)} where it had ${plain(old)}")
    }

    private fun plain(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    /**
     * The rule the whole exercise is for: a field that appears in the required
     * list of something the caller sends is a field every existing caller is
     * not sending.
     */
    private fun properties(
        where: String,
        what: String,
        before: JsonObj,
        after: JsonObj,
        direction: Direction,
        seen: Set<String>,
    ): List<ApiChange> {
        val old = before["properties"].obj()?.fields.orEmpty()
        val new = after["properties"].obj()?.fields.orEmpty()
        if (old.isEmpty() && new.isEmpty()) return emptyList()

        val wasRequired = requiredNames(before)
        val isRequired = requiredNames(after)

        return (old.keys + new.keys).flatMap { field ->
            val describe = "`$field` in $what"

            when {
                !old.containsKey(field) -> added(where, describe, field in isRequired, direction)

                !new.containsKey(field) -> one(Compatibility.BREAKING, where, "$describe is gone", gone(direction))

                else -> requiredness(where, describe, field in wasRequired, field in isRequired, direction) +
                    schema(where, describe, old[field], new[field], direction, seen)
            }
        }
    }

    private fun requiredNames(schema: JsonObj): Set<String> =
        schema["required"].arr().mapNotNull { it.str() }.toSet()

    // ----------------------------------------------------------- shared rules

    /**
     * Something the description did not have before.
     *
     * Required is the whole question. A new optional parameter or field costs an
     * existing caller nothing; a new required one refuses every request that was
     * working an hour ago, and that is the case this exists for.
     */
    private fun added(where: String, what: String, required: Boolean, direction: Direction): List<ApiChange> = when {
        direction == Direction.CALLER_RECEIVES ->
            one(Compatibility.COMPATIBLE, where, "$what is new", "a caller that ignores it is unaffected")

        required -> one(
            Compatibility.BREAKING,
            where,
            "$what is new and required",
            "every caller that is not sending it is refused",
        )

        else -> one(Compatibility.COMPATIBLE, where, "$what is new and optional")
    }

    private fun requiredness(
        where: String,
        what: String,
        was: Boolean,
        now: Boolean,
        direction: Direction,
    ): List<ApiChange> = when {
        was == now -> emptyList()

        direction == Direction.CALLER_SENDS && now ->
            one(Compatibility.BREAKING, where, "$what is required now", "a caller leaving it out is refused")

        direction == Direction.CALLER_SENDS ->
            one(Compatibility.COMPATIBLE, where, "$what is optional now")

        now -> one(Compatibility.COMPATIBLE, where, "$what is always sent now")

        else -> one(
            Compatibility.BREAKING,
            where,
            "$what may be absent now",
            "a caller reading it was promised it would be there",
        )
    }

    private fun one(compatibility: Compatibility, where: String, what: String, consequence: String = "") =
        listOf(ApiChange(compatibility, where, what, consequence))

    private fun flip(direction: Direction): Direction =
        if (direction == Direction.CALLER_SENDS) Direction.CALLER_RECEIVES else Direction.CALLER_SENDS

    /** More values allowed: harmless in a request, and a surprise in a response. */
    private fun widening(direction: Direction): Compatibility =
        if (direction == Direction.CALLER_SENDS) Compatibility.COMPATIBLE else Compatibility.BREAKING

    /** Fewer values allowed: a refusal in a request, and harmless in a response. */
    private fun narrowing(direction: Direction): Compatibility =
        if (direction == Direction.CALLER_SENDS) Compatibility.BREAKING else Compatibility.COMPATIBLE

    private fun verb(direction: Direction): String =
        if (direction == Direction.CALLER_SENDS) "accepts" else "sends"

    private fun gone(direction: Direction): String =
        if (direction == Direction.CALLER_SENDS) {
            "a caller sending it is describing a request nothing reads"
        } else {
            "a caller reading it gets nothing"
        }
}

/** Constraints that refuse more as they rise, and so break a sender when they do. */
private val TIGHTENED_BY_RISING = listOf("minimum", "minLength", "minItems")

/** And the ones that refuse more as they fall. */
private val TIGHTENED_BY_FALLING = listOf("maximum", "maxLength", "maxItems")

private fun JsonValue?.obj(): JsonObj? = this as? JsonObj

private fun JsonValue?.arr(): List<JsonValue> = (this as? JsonArr)?.items.orEmpty()

private fun JsonValue?.str(): String? = (this as? JsonStr)?.value

private fun JsonValue?.bool(): Boolean = (this as? JsonBool)?.value == true

private fun JsonValue?.num(): Double? = (this as? JsonNum)?.value?.toDouble()
