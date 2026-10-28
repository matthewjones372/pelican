package io.github.matthewjones372.pelican

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/** Why a signature did not verify. The refusal says none of it; the log says which. */
enum class SignatureFailure {
    /** A header the scheme reads was not sent. */
    MISSING,

    /** A header was sent and could not be read: no timestamp, no signature, not a number. */
    MALFORMED,

    /** The timestamp is further from now than the tolerance, either way. */
    STALE,

    /** No signature presented matches one computed with any of the secrets. */
    MISMATCH,
}

/** What [HmacScheme.verify] says of one request. */
sealed interface SignatureCheck {
    data object Valid : SignatureCheck

    data class Failed(val reason: SignatureFailure) : SignatureCheck
}

/**
 * An HMAC-SHA256 signature over a timestamp and the exact bytes of a body, as a webhook sender makes it. Made with
 * [stripeSignature] or [standardWebhooks]; [secrets] is read on every check, so a rotated secret takes effect at once.
 */
class HmacScheme internal constructor(
    private val format: SignatureFormat,
    /** How far the timestamp may be from now, either way, before the request is refused as a replay. */
    val tolerance: Duration,
    private val secrets: () -> List<String>,
) {
    /** The headers the signature arrives in, which an endpoint declares and a sender sends. */
    val headers: List<String> get() = format.headers

    /** What the signature is over, in words, for the document. */
    val description: String get() = format.description

    /** Whether [body], sent with [header]s, was signed with one of the secrets within [tolerance] of [now]. */
    fun verify(header: (String) -> String?, body: ByteArray, now: Instant): SignatureCheck {
        val presented = when (val read = format.read(header)) {
            is Read.Presented -> read
            is Read.Failed -> return SignatureCheck.Failed(read.reason)
        }
        val sent = Instant.ofEpochSecond(presented.timestamp)
        val window = tolerance.toJavaDuration()
        if (sent.isBefore(now.minus(window)) || sent.isAfter(now.plus(window))) {
            return SignatureCheck.Failed(SignatureFailure.STALE)
        }
        val content = format.content(presented, body)
        val matched = secrets().any { secret ->
            val expected = hmac(format.key(secret), content)
            // Constant time, so how much of a guess was right is not something the clock gives away.
            presented.signatures.any { MessageDigest.isEqual(expected, it) }
        }
        return if (matched) SignatureCheck.Valid else SignatureCheck.Failed(SignatureFailure.MISMATCH)
    }

    /** The headers a sender sends with [body], signed with [secret] at [at]; [id] is the event's, where it has one. */
    fun sign(
        secret: String,
        body: ByteArray,
        at: Instant,
        id: String = "msg_${UUID.randomUUID()}",
    ): List<Pair<String, String>> {
        val presented = Read.Presented(at.epochSecond, id, emptyList())
        return format.render(presented, hmac(format.key(secret), format.content(presented, body)))
    }

    override fun toString() = "HmacScheme(${format.description})"
}

/**
 * Stripe's `Stripe-Signature: t=<unix>,v1=<hex>[,v1=...]`, over `t.body`, keyed by the endpoint's signing secret as
 * Stripe shows it. [secrets] are the current ones; during a rotation, name the old one too.
 */
fun stripeSignature(tolerance: Duration = 5.minutes, secrets: () -> List<String>): HmacScheme =
    HmacScheme(StripeFormat, tolerance, secrets)

/**
 * The Standard Webhooks headers `webhook-id`, `webhook-timestamp` and `webhook-signature: v1,<base64> ...`, over
 * `id.timestamp.body`, keyed by a `whsec_` secret whose rest is the key in base64.
 */
fun standardWebhooks(tolerance: Duration = 5.minutes, secrets: () -> List<String>): HmacScheme =
    HmacScheme(StandardWebhooksFormat, tolerance, secrets)

/** What a scheme read from a request's headers: the timestamp, the event id where the scheme has one, the MACs. */
internal sealed interface Read {
    class Presented(val timestamp: Long, val id: String?, val signatures: List<ByteArray>) : Read

    class Failed(val reason: SignatureFailure) : Read
}

/** Where a scheme's signature is written, and over what. */
internal interface SignatureFormat {
    val headers: List<String>
    val description: String

    fun read(header: (String) -> String?): Read

    fun content(presented: Read.Presented, body: ByteArray): ByteArray

    fun key(secret: String): ByteArray

    fun render(presented: Read.Presented, signature: ByteArray): List<Pair<String, String>>
}

private object StripeFormat : SignatureFormat {
    private const val HEADER = "Stripe-Signature"

    override val headers = listOf(HEADER)
    override val description = "HMAC-SHA256 over `t.body`, as Stripe signs: `t=<unix seconds>,v1=<hex>`."

    override fun read(header: (String) -> String?): Read {
        val sent = header(HEADER) ?: return Read.Failed(SignatureFailure.MISSING)
        val parts = sent.split(',').map { it.trim().substringBefore('=') to it.trim().substringAfter('=', "") }
        val timestamp = parts.firstOrNull { it.first == "t" }?.second?.toLongOrNull()
        val signatures = parts.filter { it.first == "v1" }.mapNotNull { hexOrNull(it.second) }
        return if (timestamp == null || signatures.isEmpty()) {
            Read.Failed(SignatureFailure.MALFORMED)
        } else {
            Read.Presented(timestamp, id = null, signatures)
        }
    }

    override fun content(presented: Read.Presented, body: ByteArray) = "${presented.timestamp}.".toByteArray() + body

    override fun key(secret: String) = secret.toByteArray()

    override fun render(presented: Read.Presented, signature: ByteArray) =
        listOf(HEADER to "t=${presented.timestamp},v1=${HexFormat.of().formatHex(signature)}")

    private fun hexOrNull(text: String): ByteArray? =
        text.takeIf { HEX.matches(it) }?.let(HexFormat.of()::parseHex)

    private val HEX = Regex("([0-9a-f]{2})+")
}

private object StandardWebhooksFormat : SignatureFormat {
    private const val ID = "webhook-id"
    private const val TIMESTAMP = "webhook-timestamp"
    private const val SIGNATURE = "webhook-signature"
    private const val SECRET_PREFIX = "whsec_"

    override val headers = listOf(ID, TIMESTAMP, SIGNATURE)
    override val description = "HMAC-SHA256 over `id.timestamp.body`, as Standard Webhooks signs: `v1,<base64>`."

    override fun read(header: (String) -> String?): Read {
        val id = header(ID)
        val timestamp = header(TIMESTAMP)
        val signature = header(SIGNATURE)
        if (id == null || timestamp == null || signature == null) return Read.Failed(SignatureFailure.MISSING)
        val seconds = timestamp.trim().toLongOrNull()
        // Space-separated, one per secret; a version other than v1 is a scheme this does not check.
        val signatures = signature.trim().split(' ')
            .filter { it.startsWith("v1,") }
            .mapNotNull { base64OrNull(it.removePrefix("v1,")) }
        return if (seconds == null || signatures.isEmpty()) {
            Read.Failed(SignatureFailure.MALFORMED)
        } else {
            Read.Presented(seconds, id, signatures)
        }
    }

    override fun content(presented: Read.Presented, body: ByteArray) =
        "${presented.id}.${presented.timestamp}.".toByteArray() + body

    override fun key(secret: String): ByteArray = Base64.getDecoder().decode(secret.removePrefix(SECRET_PREFIX))

    override fun render(presented: Read.Presented, signature: ByteArray) = listOf(
        ID to checkNotNull(presented.id) { "A Standard Webhooks event carries an id" },
        TIMESTAMP to presented.timestamp.toString(),
        SIGNATURE to "v1," + Base64.getEncoder().encodeToString(signature),
    )

    private fun base64OrNull(text: String): ByteArray? =
        text.takeIf { it.length % BASE64_QUANTUM == 0 && BASE64.matches(it) }?.let(Base64.getDecoder()::decode)

    private const val BASE64_QUANTUM = 4
    private val BASE64 = Regex("[A-Za-z0-9+/]*={0,2}")
}

private fun hmac(key: ByteArray, content: ByteArray): ByteArray =
    Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }.doFinal(content)

private const val ALGORITHM = "HmacSHA256"
