package dev.pelican.gradle

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * The `pelican { }` block.
 *
 * Two containers rather than two properties, because a module that talks to
 * three services generates three clients, and each of them has its own spec,
 * package and output. Every entry names its tasks: `orders` gives
 * `generateOrdersClient`, `checkOrdersClient` and `generateOrdersDocument`.
 *
 * ```kotlin
 * pelican {
 *     clients {
 *         create("orders") {
 *             specClass.set("example.GenerateOpenApiKt")
 *             specFunction.set("ordersSpec")
 *             packageName.set("example.generated")
 *         }
 *     }
 *     documents {
 *         create("orders") { specClass.set("example.GenerateOpenApiKt") }
 *     }
 * }
 * ```
 */
abstract class PelicanExtension {
    abstract val clients: NamedDomainObjectContainer<ClientSpec>
    abstract val documents: NamedDomainObjectContainer<DocumentSpec>

    fun clients(action: org.gradle.api.Action<NamedDomainObjectContainer<ClientSpec>>) = action.execute(clients)

    fun documents(action: org.gradle.api.Action<NamedDomainObjectContainer<DocumentSpec>>) = action.execute(documents)
}

/**
 * Where an `ApiSpec` comes from.
 *
 * The spec is Kotlin in the consuming project, so the only way to have the
 * generated output agree with the running service is to run that code. The
 * plugin loads [specClass] off [classpath] and calls [specFunction] on it —
 * a top-level function (whose class is the file name plus `Kt`), a member of
 * an `object`, or a member of a class with a no-argument constructor.
 */
interface SpecSource {
    /**
     * The class holding the function, as the JVM names it: a top-level
     * `ordersSpec()` in `GenerateOpenApi.kt` lives in `example.GenerateOpenApiKt`.
     */
    val specClass: Property<String>

    /** The no-argument function returning the `ApiSpec`. Defaults to `spec`. */
    val specFunction: Property<String>

    /**
     * What the generator runs against: the consumer's compiled classes and
     * everything they depend on. Defaults to `main`'s runtime classpath, which
     * carries its own task dependencies — so generating compiles first without
     * anybody writing a `dependsOn`.
     *
     * `pelican-codegen` (for a client) or `pelican-openapi` (for a document)
     * has to be on it. Both are read by name and never by the plugin's own
     * classpath, which is what keeps the plugin's version and the library's
     * independent of each other.
     */
    val classpath: ConfigurableFileCollection
}

/** One generated Kotlin client. See [SpecSource] for where the spec comes from. */
abstract class ClientSpec @Inject constructor(private val name: String) : SpecSource, org.gradle.api.Named {
    override fun getName(): String = name

    /** The package the generated file declares, and the directories it lands in. */
    abstract val packageName: Property<String>

    /** The class name. Unset means the spec's title, e.g. `Orders` -> `OrdersClient`. */
    abstract val clientName: Property<String>

    /** What the client points at when its caller does not say. Unset means the spec's first server. */
    abstract val baseUrl: Property<String>

    /** Hidden endpoints are left out, as they are left out of the document. */
    abstract val includeHidden: Property<Boolean>

    /**
     * The source root written into — the generator lays out the package
     * directories underneath it. Defaults to
     * `build/generated/pelican/<name>`, which nothing checks in.
     *
     * Point it at a real source root and the generated client becomes a file
     * in the repository, reviewable in a diff. That is a supported choice, and
     * it is what turns `check<Name>Client` on: a checked-in client that no
     * longer matches the descriptions fails `check`.
     */
    abstract val outputDir: DirectoryProperty
}

/** One generated OpenAPI document. See [SpecSource] for where the spec comes from. */
abstract class DocumentSpec @Inject constructor(private val name: String) : SpecSource, org.gradle.api.Named {
    override fun getName(): String = name

    /** JSON or YAML. Both are rendered from the same document; see `openApiYaml`. */
    abstract val format: Property<DocumentFormat>

    /** Defaults to `build/generated/pelican/<name>/openapi.<format>`. */
    abstract val outputFile: RegularFileProperty
}

enum class DocumentFormat(internal val extension: String) {
    JSON("json"),
    YAML("yaml"),
}
