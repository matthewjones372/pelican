package io.github.matthewjones372.pelican.test

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.Authenticator
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.Credentials
import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.InMemoryClientTransport
import io.github.matthewjones372.pelican.Unauthenticated

/**
 * Verifies `Authorization: Bearer <subject>` against the people a test names,
 * with no provider and no keys. Bound in tests only: anyone can write that header.
 */
class TestCallers(private val people: Map<String, Identity>) : Authenticator {

    override fun authenticate(credentials: Credentials): Identity? {
        val subject = credentials.bearerToken() ?: return null
        return people[subject] ?: throw Unauthenticated("No test caller is named '$subject'")
    }

    companion object {
        /** `TestCallers.of("ada" to setOf("customer"))`: each subject, and the groups it is in. */
        fun of(vararg people: Pair<String, Set<String>>): TestCallers =
            TestCallers(people.associate { (subject, groups) -> subject to Identity(subject, subject, groups) })
    }
}

/** The same client, calling as [subject] under [TestCallers]. */
fun ApiClient.signedInAs(subject: String): ApiClient {
    val underneath = transport
    val signedIn = object : Transport {
        override fun send(request: RequestSpec): ResponseSpec =
            underneath.send(request.withHeader("Authorization", "Bearer $subject"))
    }
    return ApiClient(signedIn, codecs, prefers)
}

/**
 * A client calling [this] in memory, through the same routing, decoding and
 * handlers a bound server runs. Bind [TestCallers] on the API to call as anyone.
 */
fun Api.inMemory(): ApiClient {
    val underneath = InMemoryClientTransport(this)
    val transport = object : Transport {
        override fun send(request: RequestSpec): ResponseSpec {
            val body = request.body?.let { ClientRequest.Body.Text(it) } ?: ClientRequest.Body.Empty
            val sent = ClientRequest(request.method, "http://in-memory" + request.target, request.headers, body)
            val answer = underneath.send(sent).toCompletableFuture().join()
            return ResponseSpec(answer.status, answer.headers, answer.text())
        }
    }
    return ApiClient(transport, codecs)
}
