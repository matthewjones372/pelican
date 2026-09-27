package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.CorsPolicy
import io.github.matthewjones372.pelican.Pages
import org.apache.pekko.http.javadsl.model.StatusCodes
import org.apache.pekko.http.javadsl.model.Uri
import org.apache.pekko.http.javadsl.model.headers.RawHeader
import org.apache.pekko.http.javadsl.server.Directives
import org.apache.pekko.http.javadsl.server.Route
import java.io.File
import java.net.JarURLConnection
import java.net.URL

/**
 * [pages] as a GET route (spec 0059), last in the API's `concat` so an endpoint at the same path answers first. A
 * path no file answers rejects, so it is still the 404 or 405 it was before pages existed.
 */
internal fun pagesRoute(pages: Pages, cors: CorsPolicy?): Route = Directives.get {
    Directives.extractRequest { request ->
        val path = request.uri.path()
        val below = when {
            pages.at == "/" -> path.removePrefix("/")
            path == pages.at -> ""
            path.startsWith("${pages.at}/") -> path.removePrefix("${pages.at}/")
            else -> null
        }
        val resolved = below?.let { pages.resolve(it, ::isFile) }
        when {
            resolved == null -> Directives.reject()

            resolved.redirect -> Directives.redirect(Uri.create("$path/"), StatusCodes.MOVED_PERMANENTLY)

            cors == null -> Directives.getFromResource(resolved.resource)

            else -> {
                val origin = request.getHeader("Origin").orElse(null)?.value()
                val headers = cors.actualResponseHeaders(origin).map { (name, value) -> RawHeader.create(name, value) }
                Directives.respondWithHeaders(headers) { Directives.getFromResource(resolved.resource) }
            }
        }
    }
}

private fun classLoader(): ClassLoader = Thread.currentThread().contextClassLoader ?: Pages::class.java.classLoader

/** Whether [name] is a file on the classpath: a directory resolves to a URL too, and is not one. */
private fun isFile(name: String): Boolean {
    val url: URL = classLoader().getResource(name) ?: return false
    return when (url.protocol) {
        "file" -> File(url.toURI()).isFile
        "jar" -> (url.openConnection() as JarURLConnection).jarEntry?.isDirectory == false
        else -> true
    }
}
