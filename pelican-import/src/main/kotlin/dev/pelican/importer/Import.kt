package dev.pelican.importer

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
 * [handlers] is a [Backend] name, or null for no handler stubs.
 */
fun importEndpoints(
    document: File,
    sourceRoot: File,
    packageName: String,
    name: String,
    exclude: Set<String>,
    handlers: String?,
): List<File> = Import.write(
    document,
    ImportOptions(
        packageName = packageName,
        name = name,
        exclude = exclude,
        handlers = handlers?.let { backend ->
            Backend.entries.firstOrNull { it.name.equals(backend, ignoreCase = true) }
                ?: throw ImportFailure(
                    "No backend called '$backend'. It is one of ${Backend.entries.joinToString { it.name }}.",
                )
        },
    ),
    sourceRoot,
)

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
