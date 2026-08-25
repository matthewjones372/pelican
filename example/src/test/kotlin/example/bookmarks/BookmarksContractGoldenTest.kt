package example.bookmarks

import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.golden.Golden
import io.github.matthewjones372.pelican.test.golden.requestsOnly
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * The README's golden-file example, kept runnable so the front page cannot
 * drift — the same reason `runReadmeExample` exists for the one above it.
 *
 * The typed calls in `BookmarksContractTest` next door move with a rename and
 * stay green; what each endpoint publishes, and the bytes a caller has to send,
 * are written down here instead.
 */
class BookmarksContractGoldenTest {

    private val golden = Golden(directory = Paths.get("src", "test", "resources", "golden", "bookmarks"))
    private val calls = requestsOnly(JacksonCodecs)

    @Test
    fun `every endpoint publishes what it published`() {
        golden.operations(bookmarksSpec())
    }

    @Test
    fun `saving a bookmark builds the call its callers hold`() {
        val bookmark = CreateBookmark("https://pekko.apache.org", "Pekko", listOf("streams", "jvm"))
        golden.request("create", calls.request(createBookmark, In2("let-me-in", bookmark)))
    }
}
