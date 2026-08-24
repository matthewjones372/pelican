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

        if (codec != null) {
            throw PelicanFailure(
                "`codec` is set, and the `pelican-codegen` on this task's classpath is older than it: its " +
                    "`writeKotlinClient` takes no codec. Upgrade pelican-codegen, or remove the setting.",
            )
        }

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
    ): List<*> = writeEndpoints(
        load(loader, IMPORT, "pelican-import"),
        document,
        sourceRoot,
        packageName,
        name,
        exclude,
        handlers,
        codec,
    )

    /** The same, against a `pelican-import` already resolved; see [writeClient]. */
    @Suppress("LongParameterList")
    fun writeEndpoints(
        importer: Class<*>,
        document: File,
        sourceRoot: File,
        packageName: String,
        name: String,
        exclude: Set<String>,
        handlers: String?,
        codec: String?,
    ): List<*> {
        // The arity this plugin knows about and the arity the library on the
        // consumer's classpath offers are allowed to differ — that is the whole
        // point of looking the function up rather than compiling against it. An
        // older `pelican-import` has no `codec` parameter, and refusing to
        // import at all because of a setting nobody made would be the coupling
        // this file exists to avoid.
        val withCodec = runCatching {
            importer.getMethod(
                "importEndpoints",
                File::class.java,
                File::class.java,
                String::class.java,
                String::class.java,
                Set::class.java,
                String::class.java,
                String::class.java,
            )
        }.getOrNull()

        if (withCodec != null) {
            return withCodec.invokeUnwrapped(
                null,
                document,
                sourceRoot,
                packageName,
                name,
                exclude,
                handlers,
                codec,
            ) as List<*>
        }

        if (codec != null) {
            throw PelicanFailure(
                "`codec` is set, and the `pelican-import` on this task's classpath is older than it: its " +
                    "`importEndpoints` takes no codec. Upgrade pelican-import, or remove the setting.",
            )
        }

        val method = importer.getMethod(
            "importEndpoints",
            File::class.java,
            File::class.java,
            String::class.java,
            String::class.java,
            Set::class.java,
            String::class.java,
        )
        return method.invokeUnwrapped(null, document, sourceRoot, packageName, name, exclude, handlers) as List<*>
    }

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

/** Carries a message written for whoever has to fix it, and nothing else. */
internal class PelicanFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
