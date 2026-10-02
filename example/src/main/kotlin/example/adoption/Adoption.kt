package example.adoption

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         // the interpreter; brings pelican-core, and compiles against Pekko
 *         implementation("io.github.matthewjones372:pelican-pekko:1.0.0-RC3")
 *         // JacksonCodecs, and the schemas the document derives
 *         implementation("io.github.matthewjones372:pelican-jackson:1.0.0-RC3")
 *     }
 */

/*
 * Spec 0063's own example, kept as a service rather than as a fixture: two
 * failures under one `503`, told apart by a tag in the body instead of by
 * reading English. Its own little API rather than two more responses on the
 * orders document, because this is what `generateAdoptionClient` generates
 * from and the point is to call the result.
 */

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.pekko.handledOrFail

data class Pet(val id: Long, val name: String)

/** The shop could not write the sale down. Which attempt it was, so a caller can stop retrying. */
data class NotRecorded(val attempt: Int)

/** The chip registry could not be reached. How long until it is worth asking again. */
data class RegistryDown(val retryIn: Int)

val petId = pathParam<Long>("petId", description = "Which pet is being adopted")

val notRecorded = errorJson<NotRecorded>(503, "The sale could not be recorded")
    .tagged("not_recorded")

val registryDown = errorJson<RegistryDown>(503, "The chip registry could not be reached")
    .tagged("registry_down")

/**
 * One status, two reasons. Nothing about the declaration says which comes back
 * — the handler names the one it is answering, as it always has, and the tag it
 * was declared with travels in the body so the caller can branch on it.
 */
val adoptPet = endpoint(petId) {
    post("pets" / petId / "adoption")
    operationId = "adoptPet"
    summary = "Adopt a pet"
    json<Pet>().orFail(notRecorded, registryDown)
}

/**
 * Pet 1 is adopted, pet 2 cannot be written down, and anything else has no
 * chip registry to ask. Three answers so each branch of the generated client's
 * `when` is reached by a call rather than by a stub.
 */
fun adoptionApi() = api(
    endpoints = listOf(
        adoptPet handledOrFail { id ->
            when (id) {
                1L -> ok(Pet(1L, "Mabel"))
                2L -> notRecorded(NotRecorded(attempt = 2))
                else -> registryDown(RegistryDown(retryIn = 30))
            }
        },
    ),
    codecs = JacksonCodecs,
) {
    title = "Adoption"
    version = "1.0.0"
}
