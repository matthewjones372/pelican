package io.github.matthewjones372.pelican.spi

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.Credentials

/**
 * What [io.github.matthewjones372.pelican.Params.callerAddress] holds for a request from [peer]: the first entry of
 * [Api.callerAddressHeader] where [request] carries it, else [peer]. Either way without brackets or a port.
 */
fun Api.callerAddress(peer: String?, request: Credentials): String? {
    val trusted = callerAddressHeader?.let { request.header(it) }?.substringBefore(',')?.trim()?.ifEmpty { null }
    return (trusted ?: peer)?.let(::bareAddress)
}

/** `203.0.113.7:46532` is `203.0.113.7`, and `[2001:db8::1]:443` is `2001:db8::1`; an unbracketed IPv6 is as it is. */
internal fun bareAddress(address: String): String {
    val trimmed = address.trim()
    return when {
        trimmed.startsWith('[') -> trimmed.substring(1).substringBefore(']')
        trimmed.count { it == ':' } == 1 -> trimmed.substringBefore(':')
        else -> trimmed
    }
}
