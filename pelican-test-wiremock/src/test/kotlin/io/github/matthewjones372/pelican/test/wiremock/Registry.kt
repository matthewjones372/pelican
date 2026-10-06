package io.github.matthewjones372.pelican.test.wiremock

import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.queryParam

/** Somebody else's API, as the suite stubs it: a lookup with a query, and a write with a body. */
data class Chip(val number: String, val keeper: String)

data class NewKeeper(val keeper: String)

data class Problem(val message: String)

val petId = pathParam<Long>("petId")
val region = queryParam<String>("region")
val page = queryParam<Int>("page")
val chipNumber = pathParam<String>("chipNumber")

val noSuchChip = errorJson<Problem>(404, "No chip under that id")

val lookupChip = endpoint(petId, region, page) {
    get("chips" / petId)
    json<Chip>() orFail noSuchChip
}

val recordKeeper = endpoint(chipNumber, jsonBody<NewKeeper>()) {
    post("chips" / chipNumber / "keeper")
    json<Chip>() orFail noSuchChip
}

enum class Scan { Microchip, Tattoo }

/** A body with fields a probe cannot always vary: a closed set, and a count the registry caps. */
data class Rescan(val method: Scan, val attempts: Int, val note: String)

val tooManyAttempts = errorJson<Problem>(422, "Too many attempts")

val rescan = endpoint(chipNumber, jsonBody<Rescan>()) {
    post("chips" / chipNumber / "rescan")
    json<Rescan>().orFail(noSuchChip, tooManyAttempts)
}
