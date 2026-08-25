package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.kotest.matchers.string.shouldContain
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.http.javadsl.testkit.TestRoute
import org.apache.pekko.stream.javadsl.Source
import org.apache.pekko.util.ByteString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * `bytes() orFail e` was describable, validated and documented, and no backend
 * had a binder whose receiver fitted it — so the endpoint could be written, and
 * published, and never served. A byte stream that answers a 404 before its
 * first byte is an ordinary requirement.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BytesOrFailTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoRouteTestKit("pelican-bytes-or-fail")
    }

    private val blobId = pathParam<Long>("blobId")
    private val noSuchBlob = errorJson<ApiError>(404, "No blob with that id")

    private val fetchBlob = endpoint(blobId) {
        get("blobs" / blobId)
        bytes("application/octet-stream") orFail noSuchBlob
    }

    // `by lazy`, not a field: the extension creates the actor system in
    // `beforeAll`, which runs after this instance is constructed.
    private val route: TestRoute by lazy {
        pekko.testRoute(
            api(
                endpoints = listOf(
                    fetchBlob bytesOrFail { id ->
                        if (id == 1L) ok(Source.single(ByteString.fromString("blobby")))
                        else noSuchBlob(ApiError(404, "No blob $id"))
                    },
                ),
                codecs = JacksonCodecs,
            ).toRoute(pekko.system()),
        )
    }

    @Test
    fun `the stream is served when there is one`() {
        route.run(HttpRequest.GET("/blobs/1"))
            .assertStatusCode(200)
            .assertEntity("blobby")
    }

    @Test
    fun `and the declared failure is answered before any byte of it`() {
        route.run(HttpRequest.GET("/blobs/9"))
            .assertStatusCode(404)
            .also { it.entityString() shouldContain "No blob 9" }
    }
}
