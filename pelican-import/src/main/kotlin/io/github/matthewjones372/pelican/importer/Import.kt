package io.github.matthewjones372.pelican.importer

import io.github.matthewjones372.pelican.codegen.CodecAnnotations
import java.io.File

/**
 * An OpenAPI document in, endpoint descriptions out.
 *
 * What comes out is ordinary Kotlin in the vocabulary a hand-written service
 * uses, reaching for no server library — the file describes the API and does
 * not run it, so it serves a client and a server equally.
 *
 * The import is strict: an operation Pelican cannot describe fails the whole
 * import rather than generating an endpoint that says less than the document
 * does. [ImportOptions.exclude] leaves one out, on the record.
 *
 * [ImportOptions.discriminators] is the narrower way past one refusal: a
 * `oneOf` with no `discriminator` is a union nothing says how to read.
 */
object Import {

    /** Generated file name -> its contents. */
    fun kotlin(document: File, options: ImportOptions): Map<String, String> {
        val api = Reader(options).read(document)
        return Emitter(api, options).emit()
    }

    /**
     * Writes what [kotlin] generates into [sourceRoot], laid out by package.
     * The endpoints file is rewritten every time; a handler file is written
     * once, its whole purpose being to be filled in.
     */
    fun write(document: File, options: ImportOptions, sourceRoot: File): List<File> {
        val directory = File(sourceRoot, options.packageName.replace('.', '/'))
        directory.mkdirs()
        return kotlin(document, options).mapNotNull { (name, source) ->
            val target = File(directory, name)
            val startingPoint = name.endsWith(HANDLERS_FILE_SUFFIX)
            if (startingPoint && target.exists()) null else target.also { it.writeText(source) }
        }
    }

    /**
     * Rewrites the lockfile from what the allowed hosts serve now, and says what
     * changed. Only the bundling runs: a document with an undescribable
     * operation still has references worth locking, and blocking one fix on the
     * other helps nobody.
     */
    fun updateLock(document: File, options: ImportOptions, acceptChanges: Boolean): List<String> {
        val remote = Remote.forUpdate(options)
        Document.read(document, remote)
        return remote.update(acceptChanges)
    }
}

/**
 * The one entry the Gradle plugin calls, in types the JDK already has.
 *
 * The plugin loads this off the consumer's classpath by name, which keeps the
 * two versions independent — and stays cheap only while the signature is made
 * of `File`, `String` and `Set`. Building an [ImportOptions] reflectively would
 * put this module's constructor in the plugin's hands.
 *
 * [handlers] is a [Backend] name, [codec] a [CodecAnnotations] name;
 * [discriminators] is addressed as [Hints] describes, and [allowRemote] and
 * [lockfile] as [Remote] does.
 *
 * Every arity is declared rather than defaulted, because what the plugin looks
 * up is a signature — a default would leave the older ones as synthetic
 * bridges, forcing plugin and library releases to arrive together.
 */
@Suppress("LongParameterList") // Every parameter is one entry in the build file's `endpoints { }` block.
fun importEndpoints(
    document: File,
    sourceRoot: File,
    packageName: String,
    name: String,
    exclude: Set<String>,
    handlers: String?,
    codec: String?,
    discriminators: Map<String, String>,
    allowRemote: Set<String>,
    lockfile: File?,
): List<File> = Import.write(
    document,
    ImportOptions(
        packageName = packageName,
        name = name,
        exclude = exclude,
        discriminators = discriminators,
        allowRemote = allowRemote,
        lockfile = lockfile,
        codec = codec?.let { chosen ->
            CodecAnnotations.entries.firstOrNull { it.name.equals(chosen, ignoreCase = true) }
                ?: throw ImportFailure(
                    "No codec called '$chosen'. It is one of " +
                        CodecAnnotations.entries.joinToString { it.name } + ".",
                )
        } ?: CodecAnnotations.JACKSON,
        handlers = handlers?.let { backend ->
            Backend.entries.firstOrNull { it.name.equals(backend, ignoreCase = true) }
                ?: throw ImportFailure(
                    "No backend called '$backend'. It is one of ${Backend.entries.joinToString { it.name }}.",
                )
        },
    ),
    sourceRoot,
)

@Suppress("LongParameterList") // The arity before `allowRemote`, kept for an older plugin to find.
fun importEndpoints(
    document: File,
    sourceRoot: File,
    packageName: String,
    name: String,
    exclude: Set<String>,
    handlers: String?,
    codec: String?,
    discriminators: Map<String, String>,
): List<File> = importEndpoints(
    document,
    sourceRoot,
    packageName,
    name,
    exclude,
    handlers,
    codec,
    discriminators,
    emptySet(),
    null,
)

@Suppress("LongParameterList") // The arity before `discriminators`, kept for an older plugin to find.
fun importEndpoints(
    document: File,
    sourceRoot: File,
    packageName: String,
    name: String,
    exclude: Set<String>,
    handlers: String?,
    codec: String?,
): List<File> = importEndpoints(document, sourceRoot, packageName, name, exclude, handlers, codec, emptyMap())

/**
 * Rewrites [lockfile] from what [allowRemote] serves now, returning one line per
 * URL added, changed or dropped.
 *
 * Separate from [importEndpoints] rather than a flag on it, because it is the
 * one operation here that trusts the network — which makes "a build fetches
 * nothing this has not recorded" checkable rather than a claim about a boolean.
 *
 * [acceptChanges] is required before a recorded hash may change: adding a URL
 * shows in the diff, and changing one is the supply-chain event.
 */
fun updateRemoteLock(
    document: File,
    lockfile: File,
    name: String,
    allowRemote: Set<String>,
    acceptChanges: Boolean,
): List<String> = Import.updateLock(
    document,
    ImportOptions(packageName = "unused", name = name, allowRemote = allowRemote, lockfile = lockfile),
    acceptChanges,
)

fun importEndpoints(
    document: File,
    sourceRoot: File,
    packageName: String,
    name: String,
    exclude: Set<String>,
    handlers: String?,
): List<File> = importEndpoints(document, sourceRoot, packageName, name, exclude, handlers, null)

/**
 * What to generate.
 *
 * [name] is the one thing without a sensible default: it names the generated
 * `<name>Spec()` function and the file the endpoints land in, and a document
 * does not say what you call the service it describes.
 */
class ImportOptions(
    val packageName: String,
    val name: String = "api",

    /**
     * Operations to leave out, by `operationId` — the release valve on a strict
     * import, per-operation and written down in the build. A document with
     * three undescribable operations should generate the other two hundred and
     * say which three, so a fourth fails the build.
     */
    val exclude: Set<String> = emptySet(),

    /**
     * Schema -> the property telling the branches of its `oneOf` apart, for
     * unions a document declares without a `discriminator`.
     *
     * The refusal is not softened — a decoder still cannot try each branch and
     * keep the first that parsed. What changes is who says which branch a
     * payload is: a reader, per schema, reviewed once. See [Hints].
     *
     * A hint that stops mattering fails the import, since a claim about a
     * payload format checked against nothing is worse than none.
     */
    val discriminators: Map<String, String> = emptyMap(),

    /**
     * The hosts a `$ref` may be fetched from, as origins. Empty by default,
     * which means a `$ref` to another host fails the import.
     *
     * Naming a host trusts what it serves *once*: every URL reached and the
     * hash of what came back go into [lockfile], and a later build getting
     * different bytes fails. A global "follow references" switch would say
     * "and wherever else the document points"; this says "this host, at these
     * hashes".
     */
    val allowRemote: Set<String> = emptySet(),

    /**
     * Where the URL and hash of everything fetched is recorded and checked.
     * Required as soon as [allowRemote] names anything: there is no mode that
     * fetches without recording. See [Remote].
     */
    val lockfile: File? = null,

    /**
     * Which codec the generated payload types are annotated for. Costs a
     * document with no union nothing; a union is the exception, since
     * `sealed interface Payment` does not say which property carries the
     * branch and the two libraries spell that differently.
     *
     * Jackson by default. kotlinx.serialization is not free the same way: with
     * no reflective fallback, every payload type carries `@Serializable`.
     */
    val codec: CodecAnnotations = CodecAnnotations.JACKSON,

    /**
     * The backend to generate handler stubs for, or null for none. Written once
     * and never overwritten, so a spec-first service compiles on the first run
     * and fails only where it is called.
     */
    val handlers: Backend? = null,
)

/** Which server library the generated handler stubs bind against. */
enum class Backend(internal val packageName: String) {
    PEKKO("io.github.matthewjones372.pelican.pekko"),
    HTTP4K("io.github.matthewjones372.pelican.http4k"),
    KTOR("io.github.matthewjones372.pelican.ktor"),
}

/**
 * What a document that cannot be imported says, and why.
 *
 * One exception for the whole document rather than one per problem: the answer
 * to three unsupported operations is one decision about all three, and it
 * cannot be made from the first of them.
 */
class ImportFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal const val HANDLERS_FILE_SUFFIX = "Handlers.kt"
internal const val ENDPOINTS_FILE_SUFFIX = "Endpoints.kt"
