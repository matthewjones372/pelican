package io.github.matthewjones372.pelican.importer

import io.github.matthewjones372.pelican.codegen.CodecAnnotations
import java.io.File

/**
 * An OpenAPI document in, endpoint descriptions out.
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
     */
    val discriminators: Map<String, String> = emptyMap(),

    /**
     * The hosts a `$ref` may be fetched from, as origins. Empty by default,
     * which means a `$ref` to another host fails the import.
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
 */
class ImportFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal const val HANDLERS_FILE_SUFFIX = "Handlers.kt"
internal const val ENDPOINTS_FILE_SUFFIX = "Endpoints.kt"
