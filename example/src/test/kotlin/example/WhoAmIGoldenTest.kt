package example

import dev.surefold.pelican.test.golden.Golden
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/** What a caller publishes: its requirement, and the 401 with its challenge in the refusal envelope. Strict. */
class WhoAmIGoldenTest {

    private val golden = Golden(directory = Paths.get("src", "test", "resources", "golden", "whoami"), strict = true)

    @Test
    fun `a caller publishes its requirement and the 401 it is refused with`() {
        golden.document(whoAmISpec())
    }
}
