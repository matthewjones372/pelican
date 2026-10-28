package io.github.matthewjones372.pelican.test

import io.github.matthewjones372.pelican.HmacScheme
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * The same request signed with [scheme] and [secret] at [at], as the sender would sign it, over the body it carries.
 * A test sends a valid delivery as is, a stale one by signing in the past, and a forged one by signing another body.
 */
fun RequestSpec.signedWith(scheme: HmacScheme, secret: String, at: Instant = Instant.now()): RequestSpec =
    scheme.sign(secret, body.orEmpty().toByteArray(StandardCharsets.UTF_8), at)
        .fold(this) { signed, (name, value) -> signed.withHeader(name, value) }
