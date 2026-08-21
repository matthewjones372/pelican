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
    ): File {
        val codegen = load(loader, CLIENT, "pelican-codegen")
        val apiSpec = load(loader, API_SPEC, "pelican-core")
        val method = codegen.getMethod(
            "writeKotlinClient",
            apiSpec,
            File::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
        return method.invokeUnwrapped(
            null,
            spec,
            sourceRoot,
            packageName,
            clientName ?: defaultClientName(loader, spec),
            baseUrl ?: firstServer(spec),
            includeHidden,
        ) as File
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

    private fun defaultClientName(loader: ClassLoader, spec: Any): String {
        val codegen = load(loader, CLIENT, "pelican-codegen")
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
