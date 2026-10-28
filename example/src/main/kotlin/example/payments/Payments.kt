package example.payments

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         // the interpreter; brings pelican-core and Jetty's server
 *         implementation("io.github.matthewjones372:pelican-jetty:1.0.0")
 *         // JacksonCodecs, and the schemas the document derives
 *         implementation("io.github.matthewjones372:pelican-jackson:1.0.0")
 *     }
 */

/*
 * Spec 0102: a merchant's key says who is calling (0093), a charge is safe to
 * retry (0092), and the processor's word that it settled is believed only once
 * its signature verifies (0097). One endpoint for each, so each reads on its own;
 * PaymentsTest shows where they meet.
 */

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.Authenticator
import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.InMemoryIdempotencyStore
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.StringCodec
import io.github.matthewjones372.pelican.Unauthenticated
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.handledNow
import io.github.matthewjones372.pelican.jetty.handledOrFail
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.matching
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.permission
import io.github.matthewjones372.pelican.standardWebhooks
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours

data class NewCharge(val amount: Int, val currency: String)

enum class ChargeStatus { PENDING, SETTLED, DECLINED }

data class Charge(val id: String, val amount: Int, val currency: String, val status: ChargeStatus)

/** What the processor says of a charge once it knows: settled, or declined. */
data class ProcessorEvent(val chargeId: String, val settled: Boolean)

data class ChargeNotFound(val chargeId: String, val message: String)

val merchantKey = bearerAuth(bearerFormat = null, name = "merchantKey")

/** The merchant a key belongs to. */
val merchant = authenticated(merchantKey, Identity::subject)

/** The processor signs with this; in a real service it is read from wherever secrets are kept. */
const val PROCESSOR_SECRET = "whsec_cGF5bWVudHMgcHJvY2Vzc29yIHNlY3JldA=="

val processor = standardWebhooks { listOf(PROCESSOR_SECRET) }

val chargeId = pathParam(
    "chargeId",
    StringCodec.matching(Regex("ch_[0-9]+"), "a charge id: ch_ and digits"),
    description = "The charge, as createCharge answered it",
)

val noSuchCharge = errorJson<ChargeNotFound>(404, "No charge with that id")

val createCharge = endpoint(jsonBody<NewCharge>(description = "How much, in minor units, and in what")) {
    post("charges")
    operationId = "createCharge"
    summary = "Charge a card"
    authenticatedBy(merchant)
    requires(permission("charges:write"))
    idempotencyKey()                                   // a retried POST is answered with the first charge
    json<Charge>(status = 201)
}

val getCharge = endpoint(chargeId) {
    get("charges" / chargeId)
    operationId = "getCharge"
    summary = "A charge, as it stands"
    authenticatedBy(merchant)
    requires(permission("charges:read"))
    json<Charge>() orFail noSuchCharge                 // another merchant's charge is not found, never forbidden
}

val processorEvent = endpoint(jsonBody<ProcessorEvent>(description = "What became of a charge")) {
    post("hooks" / "processor")
    operationId = "processorEvent"
    summary = "The processor says a charge settled or was declined"
    signedBy(processor)                                // no caller: the signature is who sent it
    empty(status = 204)
}

/** The charges taken, each kept under the merchant that took it. */
class Ledger {
    private val charges = ConcurrentHashMap<String, Pair<String, Charge>>()
    private val next = AtomicInteger()

    /** How many charges were ever created, so a test can see a retry created none. */
    val created: Int get() = next.get()

    fun create(merchant: String, charge: NewCharge): Charge {
        val id = "ch_${next.incrementAndGet()}"
        return Charge(id, charge.amount, charge.currency, ChargeStatus.PENDING).also { charges[id] = merchant to it }
    }

    fun find(merchant: String, id: String): Charge? = charges[id]?.takeIf { it.first == merchant }?.second

    // Not deduplicated by the event's id (spec 0097 leaves that to a later spec): settling twice leaves a charge
    // settled, so a redelivered event changes nothing. An unknown charge is acknowledged too, or the processor
    // would retry an event nothing here can use.
    fun settle(event: ProcessorEvent) {
        charges.computeIfPresent(event.chargeId) { _, (owner, charge) ->
            owner to charge.copy(status = if (event.settled) ChargeStatus.SETTLED else ChargeStatus.DECLINED)
        }
    }
}

const val ADA_KEY = "sk_test_ada"
const val ADA_VIEWER_KEY = "sk_test_ada_viewer"
const val GRACE_KEY = "sk_test_grace"

private val keys = mapOf(
    ADA_KEY to Identity("ada", claims = mapOf("scope" to "charges:read charges:write")),
    ADA_VIEWER_KEY to Identity("ada", claims = mapOf("scope" to "charges:read")),
    GRACE_KEY to Identity("grace", claims = mapOf("scope" to "charges:read charges:write")),
)

/** The keys this service issued. Any other is refused, saying so, and never repeated back. */
val merchantKeys = Authenticator { credentials ->
    credentials.bearerToken()?.let { keys[it] ?: throw Unauthenticated("That key is not one this service issued") }
}

fun paymentsRoutes(ledger: Ledger): List<ServerEndpoint> = listOf(
    createCharge handledNow { charge -> ledger.create(this[merchant], charge) },
    getCharge handledOrFail { id ->
        ledger.find(this[merchant], id)?.let { ok(it) } ?: noSuchCharge(ChargeNotFound(id, "No charge $id"))
    },
    processorEvent handledNow { event -> ledger.settle(event) },
)

fun paymentsApi(ledger: Ledger = Ledger()): Api = api(paymentsRoutes(ledger), codecs = JacksonCodecs) {
    authenticate(merchantKey, merchantKeys)
    idempotency(InMemoryIdempotencyStore(), ttl = 24.hours)
}
