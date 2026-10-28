package example

import io.github.matthewjones372.pelican.test.golden.Golden
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/** One document for the three: a caller's 401 and 403, a retry key's 409 and 422, a signature's 400. */
class PaymentsGoldenTest {

    private val golden = Golden(directory = Paths.get("src", "test", "resources", "golden", "payments"), strict = true)

    @Test
    fun `a merchant's key, a retry-safe charge and a signed webhook publish what each declares`() {
        golden.document(paymentsSpec())
    }
}
