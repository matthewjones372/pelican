package dev.pelican.importer

import dev.pelican.codegen.CodecAnnotations
import java.io.File

/**
 * An OpenAPI document in, endpoint descriptions out.
 *
 * ```
 * Import.write(File("openapi.yaml"), ImportOptions("com.example.orders"), sourceRoot)
 * ```
 *
 * What comes out is ordinary Kotlin: input values, payload types, and one
 * `endpoint(...)` per operation, in the same vocabulary a hand-written service
 * uses. Nothing is generated that has to be edited to compile, and nothing is
 * generated that reaches for a server library — the file this writes describes
 * the API and does not run it, so it serves a client and a server equally.
 *
 * The import is strict. An operation using something Pelican cannot describe
 * fails the whole import, naming the operation and what it was, rather than
 * generating an endpoint whose type quietly says less than the document does.
 * Where the document is the one you own, fix it; where it is not, name the
 * operation in [ImportOptions.exclude] and it is left out, on the record.
 *
 * One refusal has a narrower way through than losing the operation. A `oneOf`
 * with no `discriminator` is a union nothing says how to read, and
 * [ImportOptions.discriminators] is where a reader states the property the
 * document should have named — per schema, in the build file, on the record in
 * the same way.
 */
object Import {

    /** Generated file name -> its contents. */
    fun kotlin(document: File, options: ImportOptions): Map<String, String> {
        val api = Reader(options).read(document)
        return Emitter(api, options).emit()
    }

    /**
     * Writes what [kotlin] generates into [sourceRoot], laid out by package.
     *
     * The endpoints file is rewritten every time. A handler file is written
     * once and never again: its whole purpose is to be filled in, and a
     * generator that overwrote it would be deleting the only part of this
     * anybody wrote by hand.
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
     * Rewrites the lockfile from what the allowed hosts are serving now, and
     * says what changed.
     *
     * Only the bundling runs, not the whole import: what is being recorded is
     * which URLs the document reaches and what is at them, and a document with
     * an operation Pelican cannot describe still has references worth locking.
     * Refusing to update the lockfile until the import succeeds would make one
     * problem block the fix for the other.
     */
    fun updateLock(document: File, options: ImportOptions, acceptChanges: Boolean): List<String> {
        val remote = Remote.forUpdate(options)
        Document.read(document, remote)
        return remote.update(acceptChanges)
    }
}

/**
 * The one entry the Gradle plugin calls, taking and returning only types the
 * JDK already has.
 *
 * The plugin does not compile against this module — it loads it off the
 * consumer's own classpath by name, so that the plugin's version and the
 * library's stay independent. That is only cheap while the signature it looks
 * up is made of `File`, `String` and `Set`; a reflective call that had to
 * build an [ImportOptions] first would put this module's constructor in the
 * plugin's hands, which is the coupling being avoided.
 *
 * [handlers] is a [Backend] name, or null for no handler stubs; [codec] is a
 * [CodecAnnotations] name, or null for the default; [discriminators] is the
 * per-schema discriminator hints, addressed as [Hints] describes;
 * [allowRemote] is the hosts a `$ref` may be fetched from and [lockfile] is
 * where the hash of everything fetched is recorded, both as [Remote]
 * describes.
 *
 * Every arity is declared rather than one with defaults, because what the
 * plugin looks up is a signature: a defaulted parameter would leave the older
 * ones existing only as synthetic bridges, and a plugin release and a library
 * release would have to arrive together.
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
 * Rewrites [lockfile] from what [allowRemote] is serving now, and returns one
 * line per URL added, changed or dropped.
 *
 * The second entry point the plugin calls, and made of the same JDK types as
 * the first for the same reason. It is separate from [importEndpoints] rather
 * than a flag on it because it is the one operation here that trusts the
 * network: a build fetches nothing this has not already recorded, and keeping
 * the two apart is what makes that sentence checkable rather than a claim
 * about which branch a boolean took.
 *
 * [acceptChanges] is required before a hash that is already recorded may
 * change. Adding a URL nobody had recorded is new review surface and shows up
 * in the diff as such; *changing* one is the supply-chain event, and it is
 * worth a second word on the command line.
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
     * Operations to leave out, by `operationId`.
     *
     * This is the release valve on a strict import, and it is deliberately
     * per-operation and written down in the build. A document with three
     * operations Pelican cannot describe should generate the other two hundred
     * — but it should say which three, in a file somebody reviews, so that a
     * fourth one appearing next quarter fails the build instead of quietly
     * joining them.
     */
    val exclude: Set<String> = emptySet(),

    /**
     * Schema -> the property that tells the branches of its `oneOf` apart,
     * for the unions a document declares without a `discriminator`.
     *
     * The refusal these get past is not being softened: a decoder still cannot
     * try each branch and keep the first that parsed. What changes is who says
     * which branch a payload is. The document did not, and a reader who knows
     * writes it down here — per schema, in the build file, reviewed once, the
     * way [exclude] is. See [Hints] for how a schema is addressed and where
     * each branch's wire value comes from.
     *
     * A hint that stops mattering fails the import rather than doing nothing:
     * the document stating its own `discriminator`, or nothing reaching the
     * schema any more, both leave a claim about a payload format that is
     * checked against nothing.
     */
    val discriminators: Map<String, String> = emptyMap(),

    /**
     * The hosts a `$ref` may be fetched from, as origins — `example.com`,
     * `https://example.com`, `http://mirror.internal:8080`.
     *
     * Empty by default, and empty is the whole of the old behaviour: a `$ref`
     * to another host fails the import. Naming a host does not make the build
     * trust what it serves, only what it serves *once*: every URL reached and
     * the hash of what came back are written to [lockfile], and a later build
     * that gets different bytes fails rather than generating different code.
     *
     * Per host, in the build file, reviewed once — the shape [exclude] and
     * [discriminators] have, for the reason they have it. A global "follow
     * references" switch would have answered a different question: it says
     * "and wherever else the document points", where this says "this host, and
     * these documents at these hashes".
     */
    val allowRemote: Set<String> = emptySet(),

    /**
     * Where the URL and hash of everything fetched is recorded, and checked
     * against on every later build.
     *
     * Required as soon as [allowRemote] names anything: there is no mode that
     * fetches without recording. See [Remote] for the format, the cache beside
     * it that makes an offline build possible, and how it is updated.
     */
    val lockfile: File? = null,

    /**
     * Which codec the generated payload types are annotated for.
     *
     * It costs a document with no union nothing: no annotation is written
     * unless a sealed hierarchy is generated, and nothing else in the file has
     * ever needed one. A union is the exception — `sealed interface Payment`
     * does not say which property carries the branch or what string selects
     * each one, and the two libraries spell that differently — so the file is
     * annotated for whichever one will be reading the bodies.
     *
     * Jackson by default, because `pelican-jackson` is the default codec
     * module. Choosing kotlinx.serialization is not free in the same way: it
     * has no reflective fallback, so every generated payload type carries
     * `@Serializable` under it.
     */
    val codec: CodecAnnotations = CodecAnnotations.JACKSON,

    /**
     * The backend to generate handler stubs for, or null for none.
     *
     * The stubs are written once and never overwritten. They exist so that a
     * spec-first service compiles on the first run and fails only where it is
     * called — a `TODO()` per operation is the honest state of a service
     * nobody has written yet.
     */
    val handlers: Backend? = null,
)

/** Which server library the generated handler stubs bind against. */
enum class Backend(internal val packageName: String) {
    PEKKO("dev.pelican.pekko"),
    HTTP4K("dev.pelican.http4k"),
    KTOR("dev.pelican.ktor"),
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
