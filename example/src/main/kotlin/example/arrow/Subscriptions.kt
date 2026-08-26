package example.arrow

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         // startWithDocs, /openapi.json and Swagger UI
 *         implementation("io.github.matthewjones372:pelican-pekko-docs:1.0.0-RC1")
 *         // the interpreter; brings pelican-core and Pekko HTTP
 *         implementation("io.github.matthewjones372:pelican-pekko:1.0.0-RC1")
 *         // JacksonCodecs, and the schemas the document derives
 *         implementation("io.github.matthewjones372:pelican-jackson:1.0.0-RC1")
 *         // Either into Outcome and back
 *         implementation("io.github.matthewjones372:pelican-arrow:1.0.0-RC1")
 *     }
 */

import arrow.core.Either
import arrow.core.EitherNel
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.zipOrAccumulate
import arrow.core.right
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiSpec
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.arrow.toOutcome
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.openapi.openApiJson
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.github.matthewjones372.pelican.pekko.handledOrFail

/*
 * A service whose domain is written in Arrow, described by Pelican.
 *
 * The point is what is *absent*: the domain below returns `Either` and knows
 * nothing about HTTP, statuses or Pelican, and the handlers convert at the
 * edge in one call. Nothing is wrapped in `runCatching`, no exception is
 * thrown to be caught by a layer above, and there is no second error model
 * beside the declared one.
 *
 * The three handlers are the three shapes an Arrow codebase actually meets:
 *
 *   one declared failure        `subscription(code).toOutcome()`
 *   several, chosen by the left `error.fold(...)` naming each declaration
 *   several problems at once    accumulated, then named: `toOutcome(invalid)`
 *
 * `toOutcome()` with no argument means the endpoint's single declared failure
 * — the `err` half of `ok`. Where an endpoint declares several, the mapping
 * from a domain error to a declared response is a decision the service makes,
 * so the conversion asks for it rather than guessing: the declaration is what
 * fixes the status.
 */

// ============================================================ 1. the models

data class Plan(val code: String, val name: String, val monthlyPence: Int)

data class Subscription(val id: Long, val plan: String, val email: String)

data class Signup(val email: String, val planCode: String, val seats: Int)

/** What every declared failure on this service carries. */
data class Problem(val code: String, val detail: String)

/** What the accumulating one carries instead: every rule the request broke. */
data class Invalid(val problems: List<String>)

// ====================================================== 2. the domain, in Arrow

/**
 * The vocabulary the till fails in. A sealed type rather than an exception
 * hierarchy, so the `when` that maps it to declared responses is one the
 * compiler completes.
 */
sealed interface SubscribeError {
    data class NoSuchPlan(val code: String) : SubscribeError
    data class AlreadySubscribed(val email: String) : SubscribeError
    data class SeatsExceedPlan(val asked: Int, val allowed: Int) : SubscribeError
}

/**
 * Ordinary Arrow. Nothing here imports Pelican, and this file would compile
 * with the HTTP layer deleted — which is the property the example is for.
 */
object Billing {

    private val plans = listOf(
        Plan("solo", "Solo", monthlyPence = 900),
        Plan("team", "Team", monthlyPence = 4_900),
    )

    private val seatsAllowed = mapOf("solo" to 1, "team" to 25)

    private val taken = mutableSetOf("ada@example.com")

    private var nextId = 1L

    fun plan(code: String): Either<Problem, Plan> =
        plans.firstOrNull { it.code == code }?.right()
            ?: Problem("no_such_plan", "No plan called '$code'").left()

    fun subscribe(signup: Signup): Either<SubscribeError, Subscription> = either {
        val plan = plans.firstOrNull { it.code == signup.planCode }
        ensure(plan != null) { SubscribeError.NoSuchPlan(signup.planCode) }
        ensure(signup.email !in taken) { SubscribeError.AlreadySubscribed(signup.email) }

        val allowed = seatsAllowed.getValue(plan.code)
        ensure(signup.seats <= allowed) { SubscribeError.SeatsExceedPlan(signup.seats, allowed) }

        taken += signup.email
        Subscription(nextId++, plan.code, signup.email)
    }

    /**
     * Every rule at once rather than the first one broken, which is the thing
     * `Either` alone cannot do and the reason a form gets its own error type:
     * a caller filling in three fields wrongly should be told three times.
     */
    fun validate(signup: Signup): EitherNel<String, Signup> = either {
        zipOrAccumulate(
            { ensure("@" in signup.email) { "email: '${signup.email}' is not an email address" } },
            { ensure(signup.planCode.isNotBlank()) { "planCode: name the plan to subscribe to" } },
            { ensure(signup.seats >= 1) { "seats: ask for at least one seat" } },
        ) { _, _, _ -> signup }
    }
}

// ========================================================= 3. the descriptions

private val planCode = pathParam<String>("planCode", description = "Which plan")

private val newSignup = jsonBody<Signup>("Who is subscribing, and to what")

val planMissing = errorJson<Problem>(404, "No plan with that code")
val alreadySubscribed = errorJson<Problem>(409, "That address is already subscribed")
val seatsExceedPlan = errorJson<Problem>(422, "The plan does not carry that many seats")
val invalidSignup = errorJson<Invalid>(400, "The signup broke one or more rules")

val getPlan = endpoint(planCode) {
    get("plans" / planCode)
    summary = "Fetch one plan"
    json<Plan>() orFail planMissing
}

val subscribe = endpoint(newSignup) {
    post("subscriptions")
    summary = "Subscribe an address to a plan"
    json<Subscription>(status = 201).orFail(planMissing, alreadySubscribed, seatsExceedPlan)
}

val checkSignup = endpoint(newSignup) {
    post("signups" / "check")
    summary = "Say what is wrong with a signup, all of it at once"
    json<Signup>() orFail invalidSignup
}

// ============================================================= 4. the handlers

/**
 * One declared failure, so the conversion needs no argument: a `Right` is the
 * success and a `Left` is that one failure.
 */
private val getPlanRoute = getPlan handledOrFail { code -> Billing.plan(code).toOutcome() }

/**
 * Several declared failures, so which one a `Left` becomes is this service's
 * decision and it is written here. `fold` rather than a `mapLeft` into one
 * declaration: the status is part of what each error means.
 */
private val subscribeRoute = subscribe handledOrFail { signup ->
    Billing.subscribe(signup).fold(
        { error ->
            when (error) {
                is SubscribeError.NoSuchPlan ->
                    planMissing(Problem("no_such_plan", "No plan called '${error.code}'"))

                is SubscribeError.AlreadySubscribed ->
                    alreadySubscribed(Problem("already_subscribed", "${error.email} already has a subscription"))

                is SubscribeError.SeatsExceedPlan ->
                    seatsExceedPlan(
                        Problem(
                            "seats_exceed_plan",
                            "Asked for ${error.asked} seats; the plan carries ${error.allowed}",
                        ),
                    )
            }
        },
        { subscription -> ok(subscription) },
    )
}

/**
 * The accumulating form. The domain hands back every broken rule; the payload
 * type carries them, and the naming overload says which declared response
 * that is — the endpoint declares one failure here, but naming it reads
 * better beside the `mapLeft` that built the payload.
 */
private val checkSignupRoute = checkSignup handledOrFail { signup ->
    Billing.validate(signup)
        .mapLeft { problems -> Invalid(problems.toList()) }
        .toOutcome(invalidSignup)
}

val allSubscriptionEndpoints: List<Endpoint<*, *>> = listOf(getPlan, subscribe, checkSignup)

val subscriptionRoutes: List<ServerEndpoint> = listOf(getPlanRoute, subscribeRoute, checkSignupRoute)

fun subscriptionsApi(): Api = api(subscriptionRoutes, JacksonCodecs) {
    title = "Subscriptions"
    version = "1.0.0"
    description = "A domain written in Arrow, described by Pelican."
}

fun subscriptionsSpec(): ApiSpec = apiSpec(allSubscriptionEndpoints, schemas = JacksonCodecs) {
    title = "Subscriptions"
    version = "1.0.0"
}

fun writeSubscriptionsSpec() = println(subscriptionsSpec().openApiJson())

private const val DEFAULT_PORT = 8080

/** `./gradlew :example:runArrow` — the service on :8080, docs at /api-docs. */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: DEFAULT_PORT
    val server = subscriptionsApi().startWithDocs(port = port, docs = docs { docsPath = "/api-docs" })
    println("Subscriptions on ${server.baseUrl} — docs at ${server.baseUrl}/api-docs")
}
