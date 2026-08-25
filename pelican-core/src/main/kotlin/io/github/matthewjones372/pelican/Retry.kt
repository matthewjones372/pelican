package io.github.matthewjones372.pelican

import java.io.IOException
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * When an exchange that went wrong is worth sending again, and how long to wait
 * first.
 *
 * The policy is a value rather than a switch on the client, and a client that
 * is handed no [RetryingTransport] retries nothing at all. That default is the
 * important one: a retry a caller did not ask for turns one failed call into
 * several, and the call it multiplies is the one already arriving at a service
 * in trouble. Nothing here happens unless somebody wrote it down.
 *
 * Every default below is an answer to "what is a transient failure", and each
 * is narrow on purpose, because the cost of retrying something that was not
 * transient is paid by the server rather than by whoever chose the default.
 *
 * @param maxAttempts how many times the request is sent in total, the first
 *   included, so `1` is a policy that never retries. Three is two retries: the
 *   first covers the pooled connection the server closed while it was idle and
 *   the node that went away between two calls, and the second covers the retry
 *   that happened to land on the same unhealthy node. A fourth attempt is
 *   queueing work against a service that has now failed three times, and the
 *   caller's own timeout is a better instrument for that than a longer chain.
 * @param initialBackoff how long to wait before the second attempt.
 * @param backoffMultiplier what each wait is multiplied by for the attempt
 *   after it. Doubling is the usual curve and is here for the usual reason: it
 *   reaches a wait long enough to matter within the two retries this policy
 *   allows, without a first retry so slow that a caller notices it.
 * @param maxBackoff a ceiling on that curve, so raising [maxAttempts] does not
 *   silently turn one call into a minute of waiting inside somebody's request.
 * @param jitter what fraction of each wait is decided at random, spreading it
 *   over `[wait * (1 - jitter), wait]`. Half, because clients that failed
 *   together retry together: a fleet that lost one node computes the same
 *   backoff at the same instant and re-forms the same thundering herd, and
 *   randomising the whole wait instead would let it collapse to nothing.
 * @param statuses the responses worth sending again. Deliberately without 500:
 *   an unhandled exception in a handler is the common cause of one, sending the
 *   same request again produces the same exception, and the only thing that
 *   changed is that the service did the work twice. The five here are each a
 *   statement that the request did not get a fair hearing — 408 the server
 *   giving up on reading it, 429 an explicit "later", and 502, 503 and 504 an
 *   intermediary saying it could not reach a healthy backend or was not
 *   answered in time.
 * @param methods the methods safe to send twice, which is HTTP's own list of
 *   the idempotent ones. POST and PATCH are missing because the description
 *   cannot tell us whether a second one would place a second order; a caller
 *   whose POST carries an idempotency key knows that and can say so by naming
 *   the method here.
 * @param retryStreamedBodies whether a request whose body is a
 *   [ClientRequest.Body.Streaming] may be sent again. Off by default, because
 *   whether `open()` can be called a second time is a fact about the function
 *   that was passed and nothing here can see it. The generated client's own
 *   streamed bodies cannot: a raw body is the `InputStream` its caller handed
 *   over, and a multipart file part is an `UploadedFile`, which holds one
 *   stream and hands that same one out. A supplier that opens a file by name,
 *   or one over a `ByteArray`, can — and turning this on is how its owner says
 *   so. When it is on, the request is handed to the transport again unchanged
 *   and the transport calls `open()` for itself, so what goes out is a fresh
 *   stream rather than the drained tail of the last one.
 * @param honourRetryAfter whether a `Retry-After` on the response replaces the
 *   computed wait where it is longer. A server that names a number has said
 *   something better informed than any curve here, and waiting less than it
 *   asked for is the one behaviour that is certainly wrong. Only the
 *   delta-seconds form is read: the HTTP-date form would have to be compared
 *   against this machine's clock, and importing the skew between two clocks
 *   into a wait we already have a defensible value for buys nothing.
 * @param retryAfterCap how long a `Retry-After` may ask for before this policy
 *   stops retrying rather than waits. Past it the answer is handed back as it
 *   stands — the server has said it will not be ready inside anything the
 *   caller would call a call, and holding the caller there is worse than
 *   letting it see the 503.
 * @param failures which thrown failures are worth another attempt. An
 *   `IOException` is a connection refused, a connection reset, a request that
 *   timed out — the socket-level accidents that a second attempt genuinely may
 *   not repeat. Anything else is a bug or a refusal, and both survive being
 *   sent again.
 */
class RetryPolicy internal constructor(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val initialBackoff: Duration = Duration.ofMillis(DEFAULT_INITIAL_BACKOFF_MILLIS),
    val backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER,
    val maxBackoff: Duration = Duration.ofSeconds(DEFAULT_MAX_BACKOFF_SECONDS),
    val jitter: Double = DEFAULT_JITTER,
    val statuses: Set<Int> = TRANSIENT_STATUSES,
    val methods: Set<Method> = IDEMPOTENT_METHODS,
    val retryStreamedBodies: Boolean = false,
    val honourRetryAfter: Boolean = true,
    val retryAfterCap: Duration = Duration.ofSeconds(DEFAULT_RETRY_AFTER_CAP_SECONDS),
    val failures: (Throwable) -> Boolean = { it is IOException },
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts is how many times the request is sent, so it is at least 1." }
        require(jitter in 0.0..1.0) { "jitter is the fraction of a wait decided at random, so it is 0.0 to 1.0." }
        require(!initialBackoff.isNegative) { "initialBackoff cannot be negative." }
    }

    /**
     * How long to wait before sending [request] again, having received
     * [response] on attempt number [attempt], or null to hand that response
     * back as it stands.
     *
     * The response is the one the caller would otherwise have received, so a
     * status this policy does not retry is not an error here: it is the answer.
     */
    fun retryDelay(request: ClientRequest, response: ClientResponse, attempt: Int): Duration? {
        if (!worthRepeating(request, attempt)) return null
        if (response.status !in statuses) return null

        val asked = if (honourRetryAfter) retryAfter(response) else null
        if (asked != null && asked > retryAfterCap) return null

        val computed = backoff(attempt)
        return if (asked == null || asked < computed) computed else asked
    }

    /**
     * The same question for an attempt that raised rather than answered.
     * [failure] is what the transport actually raised, already unwrapped from
     * whatever the `CompletionStage` put around it.
     */
    fun retryDelay(request: ClientRequest, failure: Throwable, attempt: Int): Duration? =
        if (worthRepeating(request, attempt) && failures(failure)) backoff(attempt) else null

    /**
     * The three questions that have nothing to do with what came back: whether
     * there is an attempt left, whether this method may be sent twice at all,
     * and whether the body could be produced a second time.
     */
    private fun worthRepeating(request: ClientRequest, attempt: Int): Boolean =
        attempt < maxAttempts &&
            request.method in methods &&
            (retryStreamedBodies || request.body !is ClientRequest.Body.Streaming)

    /**
     * The wait after attempt [attempt], jittered.
     *
     * Computed in milliseconds because that is the resolution anything
     * scheduling it has, and floored at zero so that a policy configured with
     * no initial backoff waits no time rather than a negative one.
     */
    private fun backoff(attempt: Int): Duration {
        val curve = initialBackoff.toMillis().toDouble() * backoffMultiplier.pow(attempt - 1)
        val capped = min(curve, maxBackoff.toMillis().toDouble())
        val lowest = capped * (1.0 - jitter)
        val chosen = if (capped <= lowest) capped else Random.nextDouble(lowest, capped)
        return Duration.ofMillis(max(0.0, chosen).toLong())
    }

    /**
     * What the response asked for, in the delta-seconds form and no other. A
     * header carrying anything else is read as though it were absent, which
     * leaves the computed backoff in charge — the same place a response with no
     * header at all leaves it.
     */
    private fun retryAfter(response: ClientResponse): Duration? =
        response.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let(Duration::ofSeconds)
}

/**
 * A [RetryPolicy] as its block describes it, and the defaults above where it
 * says nothing.
 *
 * A block rather than eleven constructor parameters, for the reason in [api]:
 * a policy gains a setting without every caller having to be recompiled.
 */
fun retryPolicy(configure: RetryPolicyBuilder.() -> Unit = {}): RetryPolicy =
    RetryPolicyBuilder().apply(configure).build()

/** What [retryPolicy]'s block writes into. Each setting is documented on [RetryPolicy]. */
class RetryPolicyBuilder internal constructor() {

    var maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
    var initialBackoff: Duration = Duration.ofMillis(DEFAULT_INITIAL_BACKOFF_MILLIS)
    var backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER
    var maxBackoff: Duration = Duration.ofSeconds(DEFAULT_MAX_BACKOFF_SECONDS)
    var jitter: Double = DEFAULT_JITTER
    var statuses: Set<Int> = TRANSIENT_STATUSES
    var methods: Set<Method> = IDEMPOTENT_METHODS
    var retryStreamedBodies: Boolean = false
    var honourRetryAfter: Boolean = true
    var retryAfterCap: Duration = Duration.ofSeconds(DEFAULT_RETRY_AFTER_CAP_SECONDS)
    var failures: (Throwable) -> Boolean = { it is IOException }

    internal fun build(): RetryPolicy = RetryPolicy(
        maxAttempts = maxAttempts,
        initialBackoff = initialBackoff,
        backoffMultiplier = backoffMultiplier,
        maxBackoff = maxBackoff,
        jitter = jitter,
        statuses = statuses,
        methods = methods,
        retryStreamedBodies = retryStreamedBodies,
        honourRetryAfter = honourRetryAfter,
        retryAfterCap = retryAfterCap,
        failures = failures,
    )
}

/**
 * A [ClientTransport] that sends again when [policy] says the last attempt was
 * worth repeating.
 *
 * A decorator rather than something the generator emits, which is the whole
 * reason the transport is an interface: retrying, logging and metering are all
 * the same shape — a transport wrapped around a transport — and none of them
 * needs a line per operation in a generated file. Wrapping is also what makes
 * the behaviour visible at the point somebody chose it:
 *
 * ```kotlin
 * val client = OrdersClient(
 *     "https://orders.internal",
 *     JacksonCodecs,
 *     ClientTransport.default().retrying(),
 * )
 * ```
 *
 * Nothing is blocked while a retry waits. The pause is scheduled and the next
 * attempt starts on the scheduler's thread, so a policy that waits two seconds
 * costs a timer entry rather than a parked thread — which is what the
 * asynchronous shape of `send` was for.
 *
 * A response that is going to be retried has its body closed first. It is a
 * live connection until somebody closes it, and a decorator that dropped it on
 * the floor would leak one per retry.
 */
class RetryingTransport(
    private val delegate: ClientTransport,
    private val policy: RetryPolicy = retryPolicy(),
) : ClientTransport {

    override fun send(request: ClientRequest): CompletionStage<ClientResponse> {
        val answer = CompletableFuture<ClientResponse>()
        attempt(request, 1, answer)
        return answer
    }

    /**
     * One attempt, with [answer] the future the caller is holding.
     *
     * The caller's future is created up front and completed by whichever
     * attempt turns out to be the last, rather than being the last attempt's
     * own stage, because those are the two different things a cancellation has
     * to be able to reach: cancelling what the caller holds has to stop the
     * exchange that is in flight *and* stop the retry that was going to follow
     * it.
     */
    private fun attempt(request: ClientRequest, number: Int, answer: CompletableFuture<ClientResponse>) {
        if (answer.isDone) return

        // Not guarded against a transport that throws rather than answering
        // with a failed stage: that failure reaches the caller exactly as it
        // would have without this decorator in the way, which is the right
        // place for it. A retry is for an attempt that was made.
        val sent = delegate.send(request).toCompletableFuture()

        // A dependent stage's cancellation does not travel back up the chain it
        // was derived from, so the exchange has to be cancelled here, by hand,
        // when the caller cancels what it was given.
        answer.whenComplete { _, failure -> if (failure is CancellationException) sent.cancel(true) }
        sent.whenComplete { response, failure -> decide(request, number, answer, response, failure) }
    }

    /**
     * What happens to one attempt's result: the caller's answer, or a wait and
     * another attempt.
     */
    private fun decide(
        request: ClientRequest,
        number: Int,
        answer: CompletableFuture<ClientResponse>,
        response: ClientResponse?,
        failure: Throwable?,
    ) {
        // Cancelled while this attempt was in flight. The response, if one
        // arrived anyway, is a connection nobody is going to read.
        if (answer.isDone) {
            response?.body?.close()
            return
        }

        if (failure == null && response != null) {
            val wait = policy.retryDelay(request, response, number)
            if (wait == null) {
                answer.complete(response)
            } else {
                response.body.close()
                schedule(wait) { attempt(request, number + 1, answer) }
            }
            return
        }

        // What a `CompletionStage` hands a callback is wrapped; what a caller
        // is owed is what the transport actually raised, which is the same
        // unwrapping the generated client does on the blocking path.
        val raised = unwrapped(checkNotNull(failure))
        val wait = if (raised is CancellationException) null else policy.retryDelay(request, raised, number)
        if (wait == null) {
            answer.completeExceptionally(raised)
        } else {
            schedule(wait) { attempt(request, number + 1, answer) }
        }
    }

    private fun schedule(wait: Duration, next: () -> Unit) = delayed(wait).execute(next)

    /**
     * The JDK's own delayed executor, which runs the task on a shared daemon
     * timer thread. `Thread.sleep` would have turned a wait into a parked
     * thread per call in flight, which is exactly what a client that answers
     * with a stage exists to avoid.
     */
    private fun delayed(wait: Duration): Executor =
        CompletableFuture.delayedExecutor(wait.toMillis(), TimeUnit.MILLISECONDS)
}

/**
 * The same transport with [policy] wrapped around it, which is how this reads
 * at the point a client is constructed.
 */
fun ClientTransport.retrying(policy: RetryPolicy = retryPolicy()): ClientTransport = RetryingTransport(this, policy)

/** What a `CompletionStage` callback was handed, unwrapped to what was thrown. */
private fun unwrapped(failure: Throwable): Throwable =
    if (failure is CompletionException) failure.cause ?: failure else failure

/**
 * The statuses [RetryPolicy] retries unless it is told otherwise. Each one says
 * the request did not get a fair hearing; see the class documentation for why
 * 500 is not among them.
 */
val TRANSIENT_STATUSES: Set<Int> = setOf(408, 429, 502, 503, 504)

/**
 * The methods HTTP itself defines as idempotent, which is the only thing a
 * description knows about whether a second send would do a second thing.
 * `Method` has no TRACE, so this is the whole of the list Pelican can describe.
 */
val IDEMPOTENT_METHODS: Set<Method> = setOf(Method.GET, Method.HEAD, Method.PUT, Method.DELETE, Method.OPTIONS)

private const val DEFAULT_MAX_ATTEMPTS = 3
private const val DEFAULT_INITIAL_BACKOFF_MILLIS = 100L
private const val DEFAULT_BACKOFF_MULTIPLIER = 2.0
private const val DEFAULT_MAX_BACKOFF_SECONDS = 2L
private const val DEFAULT_JITTER = 0.5
private const val DEFAULT_RETRY_AFTER_CAP_SECONDS = 10L
