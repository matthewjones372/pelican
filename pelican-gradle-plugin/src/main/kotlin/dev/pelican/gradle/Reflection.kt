package dev.pelican.gradle

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Everything the plugin knows about Pelican, in one file, by name.
 *
 * The plugin does not compile against `pelican-core`, `pelican-codegen` or
 * `pelican-openapi`. It reaches them through the classpath the consumer
 * already has, which is what allows a project to move to a new library version
 * without waiting for a plugin release — and what stops this build from
 * needing the very modules it is included by.
 *
 * The cost is that a wrong name is a runtime failure rather than a compile
 * one, so every lookup here reports what it was looking for and where.
 */
internal object Pelican {

    private const val API_SPEC = "dev.pelican.ApiSpec"
    private const val CLIENT = "dev.pelican.codegen.KotlinClientKt"
    private const val OPEN_API = "dev.pelican.openapi.OpenApiKt"
    private const val YAML = "dev.pelican.openapi.YamlKt"
    private const val IMPORT = "dev.pelican.importer.ImportKt"
    private const val CODEC_ANNOTATIONS = "dev.pelican.codegen.CodecAnnotations"

    /**
     * What an entry naming no codec means, spelled the way the library spells
     * it.
     *
     * A copy of a default the library owns, for the same reason [firstServer]
     * is a copy of another one: the value has to be in hand before the call,
     * and the call is the only thing that could have been asked for it. A
     * library that changed its default would need this line changed with it,
     * which is cheaper than an entry point existing only to be asked.
     */
    private const val DEFAULT_CODEC = "JACKSON"

    // The modules and the functions a fallback names when it refuses, spelled
    // as the reader's build file and their dependency block spell them.
    private const val CODEGEN_MODULE = "pelican-codegen"
    private const val IMPORT_MODULE = "pelican-import"
    private const val WRITES_CLIENT = "writeKotlinClient"
    private const val IMPORTS = "importEndpoints"

    /** The `discriminator(...)` entry, named as the build file writes it. */
    private const val HINTS = "discriminator(...)"

    /** The `allowRemote(...)` entry, likewise. */
    private const val REMOTE = "allowRemote(...)"

    /** The function that rewrites the lockfile, named as `pelican-import` publishes it. */
    private const val UPDATES_LOCK = "updateRemoteLock"

    /** Calls the named no-argument function and returns whatever it produced. */
    fun spec(loader: ClassLoader, className: String, functionName: String): Any {
        val type = specClass(loader, className, functionName)
        val method = specFunction(type, className, functionName)
        val receiver = if (Modifier.isStatic(method.modifiers)) null else instanceOf(type, functionName)
        val result = method.invokeUnwrapped(receiver)
            ?: throw PelicanFailure("`$className.$functionName()` returned null; an ApiSpec was expected.")

        val apiSpec = load(loader, API_SPEC, "pelican-core")
        if (!apiSpec.isInstance(result)) {
            throw PelicanFailure(
                "`$className.$functionName()` returned a ${result.javaClass.name}, not an $API_SPEC.",
            )
        }
        return result
    }

    private fun specClass(loader: ClassLoader, className: String, functionName: String): Class<*> = try {
        Class.forName(className, true, loader)
    } catch (e: ClassNotFoundException) {
        throw PelicanFailure(
            "No class `$className` on the classpath. A top-level `fun $functionName()` in " +
                "`Orders.kt` is compiled into the class `OrdersKt`, so that is the name to give.",
            e,
        )
    }

    private fun specFunction(type: Class<*>, className: String, functionName: String): Method =
        type.methods.firstOrNull { it.name == functionName && it.parameterCount == 0 }
            ?: throw PelicanFailure(
                "`$className` has no no-argument `$functionName()`. Found: " +
                    type.methods.filter { it.parameterCount == 0 }.map { it.name }.sorted().joinToString(", "),
            )

    /** Writes the client and returns the file, exactly as `writeKotlinClient` does. */
    @Suppress("LongParameterList")
    fun writeClient(
        loader: ClassLoader,
        spec: Any,
        sourceRoot: File,
        packageName: String,
        clientName: String?,
        baseUrl: String?,
        includeHidden: Boolean,
        codec: String?,
    ): File = writeClient(
        load(loader, CLIENT, "pelican-codegen"),
        load(loader, API_SPEC, "pelican-core"),
        spec,
        sourceRoot,
        packageName,
        clientName,
        baseUrl,
        includeHidden,
        codec,
    )

    /**
     * The same, against classes that are already resolved.
     *
     * The seam is here for the test. The two arities below are two releases of
     * one library, and a single class carrying both would leave the older path
     * running nowhere but on a consumer's machine — the one place nobody is
     * watching it.
     */
    @Suppress("LongParameterList")
    fun writeClient(
        codegen: Class<*>,
        apiSpec: Class<*>,
        spec: Any,
        sourceRoot: File,
        packageName: String,
        clientName: String?,
        baseUrl: String?,
        includeHidden: Boolean,
        codec: String?,
    ): File {
        val name = clientName ?: defaultClientName(codegen, spec)
        val url = baseUrl ?: firstServer(spec)

        // The bargain `writeEndpoints` makes as well: the arity this plugin
        // knows about and the arity on the consumer's classpath are allowed to
        // differ, which is the whole point of looking the function up. The
        // signature carrying the codec is preferred wherever there is one, so
        // upgrading the library is enough to get the setting — the plugin does
        // not have to be released alongside it. A library old enough to have no
        // `CodecAnnotations` at all fails the same way and lands in the same
        // place, which is why the class is loaded inside the attempt.
        val withCodec = runCatching {
            val annotations = Class.forName(CODEC_ANNOTATIONS, true, codegen.classLoader)
            annotations to codegen.getMethod(
                "writeKotlinClient",
                apiSpec,
                File::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                annotations,
            )
        }.getOrNull()

        if (withCodec != null) {
            val (annotations, method) = withCodec
            return method.invokeUnwrapped(
                null,
                spec,
                sourceRoot,
                packageName,
                name,
                url,
                includeHidden,
                codecConstant(annotations, codec),
            ) as File
        }

        if (codec != null) throw PelicanFailure(tooOld("codec", CODEGEN_MODULE, WRITES_CLIENT, "no codec"))

        val method = codegen.getMethod(
            "writeKotlinClient",
            apiSpec,
            File::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
        return method.invokeUnwrapped(null, spec, sourceRoot, packageName, name, url, includeHidden) as File
    }

    /**
     * The enum constant the entry named, matched case-insensitively because
     * `codec.set("kotlinx")` is how a build file says it and `KOTLINX` is how
     * the library declares it.
     */
    private fun codecConstant(annotations: Class<*>, codec: String?): Any {
        val constants = annotations.enumConstants.orEmpty().filterIsInstance<Enum<*>>()
        val chosen = codec ?: DEFAULT_CODEC
        return constants.firstOrNull { it.name.equals(chosen, ignoreCase = true) }
            ?: throw PelicanFailure(
                "No codec called '$chosen'. It is one of ${constants.joinToString { it.name }}.",
            )
    }

    /**
     * Generates endpoint descriptions from a document, and returns what it
     * wrote. Unlike everything else here it loads no spec: the input is a file
     * the consumer wrote or published, not code they compiled.
     */
    @Suppress("LongParameterList")
    fun writeEndpoints(
        loader: ClassLoader,
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
    ): List<*> = writeEndpoints(
        load(loader, IMPORT, "pelican-import"),
        document,
        sourceRoot,
        packageName,
        name,
        exclude,
        handlers,
        codec,
        discriminators,
        allowRemote,
        lockfile,
    )

    /** The same, against a `pelican-import` already resolved; see [writeClient]. */
    @Suppress("LongParameterList", "ReturnCount")
    fun writeEndpoints(
        importer: Class<*>,
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
    ): List<*> {
        // The bargain `writeClient` makes, two steps longer. The arity this
        // plugin knows about and the arity the library on the consumer's
        // classpath offers are allowed to differ — that is the whole point of
        // looking the function up rather than compiling against it. Four
        // releases are reachable here: one that takes the remote allowlist and
        // its lockfile, one before it that takes the discriminator hints, one
        // before them that still takes the codec, and one before that.
        //
        // Newest first, and a fallback is only taken when the setting it
        // cannot carry was not made. Falling back past a setting somebody made
        // would silently drop it and generate the file the setting was there
        // to prevent, so each step down is guarded by a named refusal — the
        // same one `writeClient` raises, written once so the two cannot come
        // to disagree about what to say.
        val text = String::class.java

        val withRemote = importEndpoints(importer, text, text, Map::class.java, Set::class.java, File::class.java)
        if (withRemote != null) {
            return withRemote.invokeUnwrapped(
                null,
                document,
                sourceRoot,
                packageName,
                name,
                exclude,
                handlers,
                codec,
                discriminators,
                allowRemote,
                lockfile,
            ) as List<*>
        }
        // Guarded harder than the rest, because this is the one setting whose
        // absence is a *security* difference rather than a missing annotation:
        // an importer with no lockfile in its signature has no hash check
        // either, and falling back to it would fetch and generate from
        // whatever came back.
        if (allowRemote.isNotEmpty()) refuse(tooOld(REMOTE, IMPORT_MODULE, IMPORTS, "no allowlist"))

        val withHints = importEndpoints(importer, text, text, Map::class.java)
        if (withHints != null) {
            return withHints.invokeUnwrapped(
                null,
                document,
                sourceRoot,
                packageName,
                name,
                exclude,
                handlers,
                codec,
                discriminators,
            ) as List<*>
        }
        if (discriminators.isNotEmpty()) refuse(tooOld(HINTS, IMPORT_MODULE, IMPORTS, "no hints"))

        val withCodec = importEndpoints(importer, text, text)
        if (withCodec != null) {
            return withCodec
                .invokeUnwrapped(null, document, sourceRoot, packageName, name, exclude, handlers, codec) as List<*>
        }
        if (codec != null) refuse(tooOld("codec", IMPORT_MODULE, IMPORTS, "no codec"))

        val oldest = importEndpoints(importer, text) ?: refuse(
            "`${importer.name}` has no `importEndpoints` this plugin knows how to call. The " +
                "`$IMPORT_MODULE` on this task's classpath is not one this plugin supports.",
        )
        return oldest.invokeUnwrapped(null, document, sourceRoot, packageName, name, exclude, handlers) as List<*>
    }

    /**
     * Rewrites the lockfile of remote references, and returns the lines
     * describing what changed.
     *
     * No fallback ladder under this one, and deliberately so: an importer
     * without it has no lockfile at all, so there is nothing older for it to
     * mean. The refusal says which module to upgrade, which is the only useful
     * answer.
     */
    @Suppress("LongParameterList")
    fun updateLock(
        loader: ClassLoader,
        document: File,
        lockfile: File,
        name: String,
        allowRemote: Set<String>,
        acceptChanges: Boolean,
    ): List<*> = updateLock(
        load(loader, IMPORT, IMPORT_MODULE),
        document,
        lockfile,
        name,
        allowRemote,
        acceptChanges,
    )

    /** The same, against a `pelican-import` already resolved; see [writeClient]. */
    @Suppress("LongParameterList")
    fun updateLock(
        importer: Class<*>,
        document: File,
        lockfile: File,
        name: String,
        allowRemote: Set<String>,
        acceptChanges: Boolean,
    ): List<*> {
        val method = runCatching {
            importer.getMethod(
                UPDATES_LOCK,
                File::class.java,
                File::class.java,
                String::class.java,
                Set::class.java,
                Boolean::class.javaPrimitiveType,
            )
        }.getOrNull() ?: refuse(
            "`${importer.name}` has no `$UPDATES_LOCK`. The `$IMPORT_MODULE` on this task's classpath is " +
                "older than remote references, so there is no lockfile for this task to write. Upgrade " +
                "$IMPORT_MODULE, or remove the `$REMOTE` entry.",
        )
        return method.invokeUnwrapped(null, document, lockfile, name, allowRemote, acceptChanges) as List<*>
    }

    /** `importEndpoints` with these trailing parameters after the five every arity takes, or null. */
    private fun importEndpoints(importer: Class<*>, vararg trailing: Class<*>): Method? = runCatching {
        @Suppress("SpreadOperator") // Three class literals, once per task run; the copy is the readable spelling.
        importer.getMethod(
            "importEndpoints",
            File::class.java,
            File::class.java,
            String::class.java,
            String::class.java,
            Set::class.java,
            *trailing,
        )
    }.getOrNull()

    /**
     * What a setting the library cannot carry is told, in one sentence used by
     * every fallback here.
     *
     * Two chains fall back — the client generator's, one step long, and the
     * importer's, two — and each step of each is a place a setting could be
     * dropped without a word. Saying it in one function is what stops the two
     * drifting into telling a reader different things about the same problem.
     */
    private fun tooOld(setting: String, module: String, function: String, missing: String) =
        "`$setting` is set, and the `$module` on this task's classpath is older than it: its " +
            "`$function` takes $missing. Upgrade $module, or remove the setting."

    /** The document, rendered the way the entry asked for. */
    fun document(loader: ClassLoader, spec: Any, format: DocumentFormat): String {
        val apiSpec = load(loader, API_SPEC, "pelican-core")
        val (className, function) = when (format) {
            DocumentFormat.JSON -> OPEN_API to "openApiJson"
            DocumentFormat.YAML -> YAML to "openApiYaml"
        }
        val renderer = load(loader, className, "pelican-openapi")
        return renderer.getMethod(function, apiSpec).invokeUnwrapped(null, spec) as String
    }

    // ----------------------------------------------------------------------

    private fun defaultClientName(codegen: Class<*>, spec: Any): String {
        val title = property(spec, "getTitle") as String
        return codegen.getMethod("defaultClientName", String::class.java).invokeUnwrapped(null, title) as String
    }

    /** The same default `writeKotlinClient` has: the spec's first server, or none. */
    private fun firstServer(spec: Any): String? = (property(spec, "getServers") as? List<*>)?.firstOrNull() as? String

    private fun property(spec: Any, getter: String): Any? = spec.javaClass.getMethod(getter).invokeUnwrapped(spec)

    private fun load(loader: ClassLoader, className: String, module: String): Class<*> = try {
        Class.forName(className, true, loader)
    } catch (e: ClassNotFoundException) {
        throw PelicanFailure(
            "`$className` is not on the classpath this task runs against. Add `$module` to the " +
                "dependencies of the source set the spec is compiled in, or set `classpath` on the entry.",
            e,
        )
    }

    private fun instanceOf(type: Class<*>, functionName: String): Any = try {
        // A Kotlin `object` first, then anything with a no-argument constructor.
        type.getField("INSTANCE").get(null) ?: type.getDeclaredConstructor().newInstance()
    } catch (_: NoSuchFieldException) {
        try {
            type.getDeclaredConstructor().newInstance()
        } catch (e: ReflectiveOperationException) {
            throw PelicanFailure(
                "`${type.name}.$functionName()` is not static and `${type.name}` cannot be instantiated: " +
                    "it needs to be a top-level function, a member of an `object`, or a member of a class " +
                    "with a no-argument constructor.",
                e,
            )
        }
    }

    /**
     * A failure inside the consumer's own spec is the interesting one, so it
     * is rethrown as itself rather than wrapped in a reflection exception
     * nobody wants to read past.
     */
    private fun Method.invokeUnwrapped(receiver: Any?, vararg args: Any?): Any? = try {
        invoke(receiver, *args)
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }
}

/**
 * Refusing, said the way every refusal here says it.
 *
 * A `Nothing` return rather than a `throw` at each site: half the lookups
 * below are a ladder of guards, and a guard reads as one statement — `if the
 * library cannot carry this, say so` — where a branch with a throw in it reads
 * as control flow somebody has to follow.
 */
internal fun refuse(message: String): Nothing = throw PelicanFailure(message)

/** Carries a message written for whoever has to fix it, and nothing else. */
internal class PelicanFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
