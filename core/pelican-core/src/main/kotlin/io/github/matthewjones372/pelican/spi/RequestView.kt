package io.github.matthewjones372.pelican.spi

import io.github.matthewjones372.pelican.Cookies
import io.github.matthewjones372.pelican.Credentials

/**
 * A request as its backend already holds it, read in place: nothing is copied out of it until something asks. It
 * lives as long as its request and is never stored past it; what outlives the request is the values decoded from it.
 */
interface RequestView : Credentials {
    val method: String

    /** The path as the request line spelled it: not percent-decoded, its dot segments not resolved. */
    val rawPath: String

    /** Everything after the first `?`, not decoded, or null where there is no `?`. */
    val rawQuery: String?

    /** The first field line under [name], matched without regard to case. */
    override fun header(name: String): String?

    /** Every field line under [name], in arrival order; empty where there is none. */
    fun headers(name: String): List<String>

    /** Parsed by core, so a session reads the same on every backend. */
    override fun cookie(name: String): String? = Cookies.parseAll(headers(COOKIE))[name]?.firstOrNull()

    private companion object {
        const val COOKIE = "Cookie"
    }
}
