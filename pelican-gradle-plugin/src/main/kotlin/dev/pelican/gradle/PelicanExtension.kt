package dev.pelican.gradle

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
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
    abstract val endpoints: NamedDomainObjectContainer<EndpointsSpec>

    fun clients(action: org.gradle.api.Action<NamedDomainObjectContainer<ClientSpec>>) = action.execute(clients)

    fun documents(action: org.gradle.api.Action<NamedDomainObjectContainer<DocumentSpec>>) = action.execute(documents)

    fun endpoints(action: org.gradle.api.Action<NamedDomainObjectContainer<EndpointsSpec>>) = action.execute(endpoints)
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
     * Which codec the generated payload types are annotated for — `jackson`
     * or `kotlinx` — or unset for Jackson. The same setting `endpoints` takes,
     * spelled the same way, because it is the same decision: a generated
     * client's bodies are read by the same library the service's are.
     *
     * It matters for one shape and no others. A `oneOf` becomes a sealed
     * interface, and nothing in `sealed interface Payment` says which property
     * carries the branch or what string selects each one; that has to be
     * written down, and the two libraries spell it differently. A spec without
     * a union generates the same client either way.
     */
    abstract val codec: Property<String>

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

/**
 * One set of endpoint descriptions, generated *from* an OpenAPI document.
 *
 * The other entries here read a compiled `ApiSpec` and write something else.
 * This one reads a document somebody else wrote — so it has no `specClass` and
 * no `specFunction`, and its classpath needs `pelican-import` rather than the
 * consumer's own code.
 *
 * ```kotlin
 * pelican {
 *     endpoints {
 *         create("orders") {
 *             document.set(layout.projectDirectory.file("orders.yaml"))
 *             packageName.set("com.example.orders")
 *         }
 *     }
 * }
 * ```
 */
abstract class EndpointsSpec @Inject constructor(private val name: String) : org.gradle.api.Named {
    override fun getName(): String = name

    /** The OpenAPI document to read. JSON or YAML; 2.0, 3.0 and 3.1 are all read. */
    abstract val document: RegularFileProperty

    /** The package the generated file declares, and the directories it lands in. */
    abstract val packageName: Property<String>

    /**
     * Operations to leave out, by `operationId`.
     *
     * The import is strict: an operation using something Pelican cannot
     * describe fails the build rather than generating an endpoint that says
     * less than the document does. This is where the ones you have decided to
     * live without are written down, so that the next one to appear fails
     * rather than joining them quietly.
     */
    abstract val exclude: SetProperty<String>

    fun exclude(vararg operationIds: String) {
        exclude.addAll(*operationIds)
    }

    /**
     * Schema -> the property that tells the branches of its `oneOf` apart, for
     * the unions a document declares without a `discriminator`.
     *
     * Set through [discriminator] rather than written as a map, so that the
     * build file reads as a statement about one schema.
     */
    abstract val discriminators: MapProperty<String, String>

    /**
     * States which property tells a union's branches apart, where the document
     * did not.
     *
     * ```kotlin
     * discriminator("Payment", property = "kind")
     * discriminator("Order/properties/payment", property = "kind")
     * ```
     *
     * A `oneOf` with no `discriminator` is refused, because a decoder would
     * have to try each branch and keep the first that parsed — which is wrong,
     * silently, on the first payload two branches both accept. That refusal
     * stands. This is who says which branch a payload is when the document
     * does not: the reader, once, in a file somebody reviews.
     *
     * [schema] is a component name, a pointer relative to
     * `#/components/schemas` for a union written under a property, or a JSON
     * pointer from the root of the document — `#/paths/~1payments/post/...` —
     * for one written at the endpoint. A union with no name is the case that
     * makes the pointer worth having: without it, the way through would still
     * be `exclude`, and the operation would still be lost.
     *
     * The value on the wire is not invented. It is the `const` a branch
     * declares for [property], or the name of the schema the branch points at;
     * a branch written inline that declares neither fails the import rather
     * than being given a positional name no payload would carry.
     *
     * A hint that stops mattering — the document grew its own `discriminator`,
     * or nothing reaches the schema any more — fails the import. An `exclude`
     * that matches nothing has weakened nothing, and a hint that is checked
     * against nothing is a claim about a payload format nobody is verifying.
     */
    fun discriminator(schema: String, property: String) {
        discriminators.put(schema, property)
    }

    /**
     * The backend to generate handler stubs against — `pekko`, `http4k` or
     * `ktor` — or unset for none.
     *
     * The stubs are written once and never overwritten: after the first run
     * they are the service, not generated code.
     */
    abstract val handlers: Property<String>

    /**
     * Which codec the generated payload types are annotated for — `jackson`
     * or `kotlinx` — or unset for Jackson.
     *
     * It matters for one shape and no others. A `oneOf` becomes a sealed
     * interface, and nothing in `sealed interface Payment` says which property
     * carries the branch or what string selects each one; that has to be
     * written down, and the two libraries spell it differently. A document
     * without a union generates the same file either way.
     */
    abstract val codec: Property<String>

    /**
     * The source root written into. Defaults to
     * `build/generated/pelican/<name>`; point it at a real source root to have
     * the descriptions become files in the repository. Either way the
     * generated endpoints file is rewritten on every run.
     */
    abstract val outputDir: DirectoryProperty

    /** What the import runs against. `pelican-import` has to be on it. */
    abstract val classpath: ConfigurableFileCollection
}

enum class DocumentFormat(internal val extension: String) {
    JSON("json"),
    YAML("yaml"),
}
