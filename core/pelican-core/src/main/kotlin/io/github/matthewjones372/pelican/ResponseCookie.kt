package io.github.matthewjones372.pelican

import kotlin.time.Duration

/** Which cross-site requests a browser attaches a cookie to (RFC 6265bis). */
enum class SameSite(val attribute: String) {
    STRICT("Strict"),
    LAX("Lax"),
    NONE("None"),
}

/**
 * A cookie to set, written as one `Set-Cookie` header. The defaults are the safe ones: unreadable by scripts, sent only
 * over HTTPS, held back from cross-site subrequests, and for the whole site. A null [maxAge] lasts the browser session.
 */
class ResponseCookie(
    val name: String,
    val value: String,
    val maxAge: Duration? = null,
    val path: String = "/",
    val domain: String? = null,
    val httpOnly: Boolean = true,
    val secure: Boolean = true,
    val sameSite: SameSite = SameSite.LAX,
) {
    init {
        require(name.isNotEmpty() && name.all(::isTokenChar)) { "'$name' is not a cookie name: a token, RFC 6265" }
        require(value.all(::isCookieOctet)) { "The value of cookie '$name' has a character a Set-Cookie cannot carry" }
        require(path.startsWith("/") && path.all(::isAttributeChar)) { "Cookie '$name' has a path that is not one" }
        require(domain == null || (domain.isNotEmpty() && domain.all(::isAttributeChar))) {
            "Cookie '$name' has a domain that is not one"
        }
        require(sameSite != SameSite.NONE || secure) {
            "Cookie '$name' is SameSite=None without Secure, which a browser drops. Keep it Secure."
        }
        if (name.startsWith(SECURE_PREFIX) || name.startsWith(HOST_PREFIX)) {
            require(secure) { "A cookie named '$name' must be Secure, or a browser refuses it" }
        }
        if (name.startsWith(HOST_PREFIX)) {
            require(domain == null && path == "/") {
                "A cookie named '$name' must name no Domain and have Path=/, or a browser refuses it"
            }
        }
    }

    /** The `Set-Cookie` value, attributes in a fixed order. */
    val headerValue: String = buildString {
        append(name).append('=').append(value)
        append("; Path=").append(path)
        domain?.let { append("; Domain=").append(it) }
        if (httpOnly) append("; HttpOnly")
        if (secure) append("; Secure")
        append("; SameSite=").append(sameSite.attribute)
        maxAge?.let { append("; Max-Age=").append(it.inWholeSeconds.coerceAtLeast(0)) }
    }

    override fun toString() = "ResponseCookie($name)"

    companion object {
        /** Tells the browser to forget [name] now: an empty value and `Max-Age=0`, at the same path and domain. */
        fun expired(name: String, path: String = "/", domain: String? = null): ResponseCookie =
            ResponseCookie(name, "", maxAge = Duration.ZERO, path = path, domain = domain)
    }
}

private const val SECURE_PREFIX = "__Secure-"
private const val HOST_PREFIX = "__Host-"

private const val SEPARATORS = "()<>@,;:\\\"/[]?={} \t"

private fun isTokenChar(c: Char) = c.code in PRINTABLE && c !in SEPARATORS

/** RFC 6265's cookie-octet: printable US-ASCII but for space, `"`, `,`, `;` and `\`. */
private fun isCookieOctet(c: Char) = c.code in PRINTABLE && c != ' ' && c !in "\",;\\"

private fun isAttributeChar(c: Char) = c.code in PRINTABLE && c != ' ' && c != ';'

private val PRINTABLE = 0x21..0x7e
