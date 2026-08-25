package io.github.matthewjones372.pelican.gradle

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Everything the plugin knows about Pelican, in one file, by name.
 */
internal object Pelican {

    private const val API_SPEC = "io.github.matthewjones372.pelican.ApiSpec"
    private const val CLIENT = "io.github.matthewjones372.pelican.codegen.KotlinClientKt"
    private const val OPEN_API = "io.github.matthewjones372.pelican.openapi.OpenApiKt"
    private const val YAML = "io.github.matthewjones372.pelican.openapi.YamlKt"
    private const val REPORT = "io.github.matthewjones372.pelican.openapi.ReportKt"
    private const val IMPORT = "io.github.matthewjones372.pelican.importer.ImportKt"
    private const val CODEC_ANNOTATIONS = "io.github.matthewjones372.pelican.codegen.CodecAnnotations"
    private const val OPEN_API_VERSION = "io.github.matthewjones372.pelican.openapi.OpenApiVersion"

    /**
     * What an entry naming no codec means. A copy of a default the library
     * owns, because the value is needed before the call that would have
     * answered it — cheaper than an entry point existing only to be asked.
     */
    private const val DEFAULT_CODEC = "JACKSON"

    /**
     * What an entry naming no OpenAPI version means, and a copy of the
     * library's own default for the same reason [DEFAULT_CODEC] is one. It is
     * written as the document writes it, since that is the spelling a build
     * file uses.
     */
    private const val DEFAULT_OPEN_API_VERSION = "3.1.0"

    // Named as the reader's build file and dependency block spell them.
    private const val CODEGEN_MODULE = "pelican-codegen"
    private const val IMPORT_MODULE = "pelican-import"
    private const val OPEN_API_MODULE = "pelican-openapi"
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
     * The same, against classes that are already resolved. The seam is here for
     * the test: the two arities below are two releases of one library, and
     * without it the older path would run only on a consumer's machine.
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
     * The enum constant the entry named, matched case-insensitively:
     * `codec.set("kotlinx")` is how a build file says `KOTLINX`.
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
     * Generates endpoint descriptions from a document. Unlike everything else
     * here it loads no spec: the input is a file, not compiled code.
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
     */
    private fun tooOld(setting: String, module: String, function: String, missing: String) =
        "`$setting` is set, and the `$module` on this task's classpath is older than it: its " +
            "`$function` takes $missing. Upgrade $module, or remove the setting."

    /** The document, rendered the way the entry asked for. */
    fun document(loader: ClassLoader, spec: Any, format: DocumentFormat, version: String?): String {
        val apiSpec = load(loader, API_SPEC, "pelican-core")
        val (className, function) = when (format) {
            DocumentFormat.JSON -> OPEN_API to "openApiJson"
            DocumentFormat.YAML -> YAML to "openApiYaml"
        }
        return document(load(loader, className, OPEN_API_MODULE), apiSpec, function, spec, version)
    }

    /**
     * The same, against classes that are already resolved. The seam is here
     * for the test, as it is on [writeClient]: the two arities below are two
     * releases of one library, and without it the older path would run only on
     * a consumer's machine.
     */
    fun document(renderer: Class<*>, apiSpec: Class<*>, function: String, spec: Any, version: String?): String {
        // A `pelican-openapi` from before the version was selectable has a
        // renderer that takes the spec and nothing else.
        val withVersion = runCatching {
            val versions = Class.forName(OPEN_API_VERSION, true, renderer.classLoader)
            versions to renderer.getMethod(function, apiSpec, versions)
        }.getOrNull()

        if (withVersion != null) {
            val (versions, method) = withVersion
            return method.invokeUnwrapped(null, spec, versionConstant(versions, version)) as String
        }

        if (version != null) {
            throw PelicanFailure(tooOld("openApiVersion", OPEN_API_MODULE, function, "no version"))
        }

        return renderer.getMethod(function, apiSpec).invokeUnwrapped(null, spec) as String
    }

    /**
     * The enum constant the entry named. `openApiVersion.set("3.2.0")` is how
     * a build file asks for it — the number the document will carry rather
     * than the Kotlin name for it, because the number is the thing a
     * consumer's tooling dictated. The Kotlin name is accepted too, so neither
     * spelling is a mistake.
     */
    private fun versionConstant(versions: Class<*>, version: String?): Any {
        val constants = versions.enumConstants.orEmpty().filterIsInstance<Enum<*>>()
        val chosen = version ?: DEFAULT_OPEN_API_VERSION
        return constants.firstOrNull { it.toString() == chosen || it.name.equals(chosen, ignoreCase = true) }
            ?: throw PelicanFailure(
                "`openApiVersion` is set to '$chosen', and the versions `$OPEN_API_MODULE` writes are " +
                    constants.joinToString { "'$it'" } + ".",
            )
    }

    /**
     * What changed for the people already calling the service, and how many of
     * those changes break them.
     *
     * Two calls rather than one, because what crosses this boundary is a
     * `String` and an `int` — the plugin cannot hold an `ApiChange`, and a
     * shape invented to carry one over reflection would be a second API for
     * the library to keep. The comparison runs twice over two small documents;
     * that costs less than the type would.
     */
    fun compatibility(loader: ClassLoader, published: String, proposed: String, heading: String): Pair<String, Int> {
        val report = load(loader, REPORT, "pelican-openapi")
        val text = report
            .getMethod(
                "compatibilityReport",
                String::class.java,
                String::class.java,
                String::class.java,
                Boolean::class.java,
            )
            .invokeUnwrapped(null, published, proposed, heading, false) as String
        val breaking = report
            .getMethod("breakingChanges", String::class.java, String::class.java)
            .invokeUnwrapped(null, published, proposed) as Int
        return text to breaking
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
 */
internal fun refuse(message: String): Nothing = throw PelicanFailure(message)

/** Carries a message written for whoever has to fix it, and nothing else. */
internal class PelicanFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
