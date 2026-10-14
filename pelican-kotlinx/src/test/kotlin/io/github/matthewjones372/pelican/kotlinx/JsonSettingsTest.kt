package io.github.matthewjones372.pelican.kotlinx

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.junit.jupiter.api.Test

/**
 * Schemas here are derived from descriptors, and a descriptor knows nothing
 * about the `Json` that will write it. A setting that changes the bytes and not
 * the document is the contract lying, so the ones this module cannot describe
 * are refused where the codecs are built rather than honoured by half.
 */
class JsonSettingsTest {

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `a naming strategy is refused, because the document would still say camelCase`() {
        val refused = shouldThrow<IllegalArgumentException> {
            KotlinxCodecs(Json { namingStrategy = JsonNamingStrategy.SnakeCase })
        }

        refused.message shouldContain "namingStrategy"
    }

    @Test
    fun `so is a setting that only relaxes the reader`() {
        val refused = shouldThrow<IllegalArgumentException> { KotlinxCodecs(Json { isLenient = true }) }

        refused.message shouldContain "isLenient"
    }

    @Test
    fun `the four it reads are accepted, and so are the defaults`() {
        shouldNotThrowAny {
            KotlinxCodecs(defaultJson())
            KotlinxCodecs(
                Json {
                    classDiscriminator = "kind"
                    ignoreUnknownKeys = false
                    encodeDefaults = false
                    explicitNulls = true
                },
            )
        }
    }
}
