package io.github.matthewjones372.pelican

/**
 * Files from the classpath served beside an API's endpoints (spec 0059): the directory [resources], answered under
 * [at]. An endpoint's path always wins over a page, so adding a page never hides a route. Pages are not endpoints:
 * the document does not list them, and filters, which wrap a handler, do not run for them.
 */
class Pages internal constructor(val resources: String, val at: String) {

    /**
     * Which file answers [path], the request's path below [at] without its leading slash, as a classpath resource
     * name; null when none does. `x` is tried as `x`, then `x.html`, then `x/index.html`; an empty path or one
     * ending in a slash is its `index.html`. A path with a `..`, `.` or empty segment never leaves [resources]: it
     * answers nothing. [isFile] says whether a resource name is a file there.
     */
    fun resolve(path: String, isFile: (String) -> Boolean): Resolved? {
        if (!inside(path)) return null
        val base = if (path.isEmpty()) resources else "$resources/${path.trimEnd('/')}"
        if (path.isEmpty() || path.endsWith("/")) return found("$base/index.html", isFile, redirect = false)
        return found(base, isFile, redirect = false)
            ?: found("$base.html", isFile, redirect = false)
            // A directory's page answers at its slash, so the page's relative links resolve inside the directory.
            ?: found("$base/index.html", isFile, redirect = true)
    }

    private fun found(name: String, isFile: (String) -> Boolean, redirect: Boolean): Resolved? =
        if (isFile(name)) Resolved(name, redirect) else null

    private fun inside(path: String): Boolean = path.isEmpty() || path.removeSuffix("/").split('/').none(::escapes)

    private fun escapes(segment: String): Boolean =
        segment.isEmpty() || segment == "." || segment == ".." || '\\' in segment

    /** The resource that answers, and whether the request is sent to the same path with a trailing slash instead. */
    data class Resolved(val resource: String, val redirect: Boolean)
}

/** The classpath directory [resources] served under [at]: `pages = pages("ui")` in `api`'s block. */
fun pages(resources: String, at: String = "/"): Pages {
    require(resources.isNotBlank() && !resources.startsWith("/")) {
        "A classpath directory is named without a leading slash, as \"ui\", not \"$resources\""
    }
    require(at.startsWith("/")) { "Pages are served under a path that starts with a slash, not \"$at\"" }
    return Pages(resources.trimEnd('/'), at.trimEnd('/').ifEmpty { "/" })
}
