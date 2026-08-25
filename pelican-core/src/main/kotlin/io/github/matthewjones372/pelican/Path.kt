package io.github.matthewjones372.pelican

/** One element of a route's path: either a fixed word or a typed capture. */
sealed interface PathSegment {
    data class Literal(val value: String) : PathSegment
    data class Capture(val param: PathParam<*>) : PathSegment
}

/**
 * A path built with the `/` operator:
 */
class PathSpec(val segments: List<PathSegment>) {
    /** OpenAPI-style template, e.g. `/users/{userId}/orders`. */
    val template: String
        get() = "/" + segments.joinToString("/") {
            when (it) {
                is PathSegment.Literal -> it.value
                is PathSegment.Capture -> "{${it.param.name}}"
            }
        }

    val captures: List<PathParam<*>>
        get() = segments.filterIsInstance<PathSegment.Capture>().map { it.param }

    override fun toString() = template

    companion object {
        val root = PathSpec(emptyList())
    }
}

private fun seg(s: String) = PathSegment.Literal(s.trim('/'))

operator fun String.div(next: String): PathSpec = PathSpec(listOf(seg(this), seg(next)))
operator fun String.div(next: PathParam<*>): PathSpec =
    PathSpec(listOf(seg(this), PathSegment.Capture(next)))

operator fun PathSpec.div(next: String): PathSpec = PathSpec(segments + seg(next))
operator fun PathSpec.div(next: PathParam<*>): PathSpec =
    PathSpec(segments + PathSegment.Capture(next))

operator fun PathParam<*>.div(next: String): PathSpec =
    PathSpec(listOf(PathSegment.Capture(this), seg(next)))

operator fun PathParam<*>.div(next: PathParam<*>): PathSpec =
    PathSpec(listOf(PathSegment.Capture(this), PathSegment.Capture(next)))

/** Lifts a single literal or capture into a [PathSpec]. */
fun path(literal: String): PathSpec =
    PathSpec(literal.split('/').filter { it.isNotEmpty() }.map { PathSegment.Literal(it) })

fun path(param: PathParam<*>): PathSpec = PathSpec(listOf(PathSegment.Capture(param)))
