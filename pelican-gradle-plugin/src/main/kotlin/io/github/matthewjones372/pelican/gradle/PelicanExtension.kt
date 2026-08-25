package io.github.matthewjones372.pelican.gradle

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
 * Where an `ApiSpec` comes from. The spec is Kotlin in the consuming project,
 * so the only way for generated output to agree with the running service is to
 * run that code: [specClass] is loaded off [classpath] and [specFunction]
 * called on it.
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
     * What the generator runs against. Defaults to `main`'s runtime classpath,
     * which carries its own task dependencies, so generating compiles first
     * without a `dependsOn`.
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
     * Which codec the generated payload types are annotated for — `jackson` or
     * `kotlinx` — or unset for Jackson.
     */
    abstract val codec: Property<String>

    /**
     * Whether the generated methods block or suspend — `blocking` or
     * `suspending` — or unset for blocking.
     *
     * One or the other rather than both, because both would put two methods per
     * endpoint on the class and kotlinx.coroutines on the classpath of every
     * caller, including the ones that never wanted it. A `suspending` client
     * needs `org.jetbrains.kotlinx:kotlinx-coroutines-core` alongside
     * `pelican-core`; everything else about it — the method names, the
     * parameters, the payload types, the sealed failures — is the same file.
     */
    abstract val callStyle: Property<String>

    /**
     * The source root written into, defaulting to `build/generated/pelican/
     * <name>`. Point it at a real source root and the client becomes a
     * reviewable file — which is also what turns `check<Name>Client` on.
     */
    abstract val outputDir: DirectoryProperty
}

/** One generated OpenAPI document. See [SpecSource] for where the spec comes from. */
abstract class DocumentSpec @Inject constructor(private val name: String) : SpecSource, org.gradle.api.Named {
    override fun getName(): String = name

    /** JSON or YAML. Both are rendered from the same document; see `openApiYaml`. */
    abstract val format: Property<DocumentFormat>

    /**
     * Which revision of the OpenAPI Specification the document is written
     * against — `"3.1.0"` or `"3.2.0"` — or unset for 3.1.0.
     *
     * It is a real choice rather than a number that should always be the
     * newest: a consumer reading 3.1 is promised nothing about a document that
     * says 3.2, and the JVM parsers mostly still turn one into nothing at all.
     * What 3.2 buys is a document that is *correct* about cookie parameters
     * and streamed responses, which 3.1 has no way to state. The reasoning is
     * on `OpenApiVersion` and in the reference manual.
     */
    abstract val openApiVersion: Property<String>

    /** Defaults to `build/generated/pelican/<name>/openapi.<format>`. */
    abstract val outputFile: RegularFileProperty

    /**
     * The document the callers already have, where it is committed somewhere.
     *
     * Setting it registers `check<Name>Document`, which compares what the
     * descriptions produce now against that file and fails the build when a
     * difference is one an existing caller cannot survive: a new required
     * field, a deleted endpoint, a response that stopped carrying something.
     * A difference nobody has to act on is reported and does not fail.
     */
    abstract val baseline: RegularFileProperty
}

/**
 * One set of endpoint descriptions, generated *from* an OpenAPI document.
 */
abstract class EndpointsSpec @Inject constructor(private val name: String) : org.gradle.api.Named {
    override fun getName(): String = name

    /** The OpenAPI document to read. JSON or YAML; 2.0, 3.0 and 3.1 are all read. */
    abstract val document: RegularFileProperty

    /** The package the generated file declares, and the directories it lands in. */
    abstract val packageName: Property<String>

    /**
     * Operations to leave out, by `operationId`. The import is strict — an
     * operation Pelican cannot describe fails the build — so this is where the
     * ones you decided to live without are written down, and the next one to
     * appear fails rather than joining them quietly.
     */
    abstract val exclude: SetProperty<String>

    fun exclude(vararg operationIds: String) {
        exclude.addAll(*operationIds)
    }

    /**
     * Schema -> the property telling the branches of its `oneOf` apart, for
     * unions a document declares without a `discriminator`. Set through
     * [discriminator], so the build file reads as a statement about one schema.
     */
    abstract val discriminators: MapProperty<String, String>

    /**
     * States which property tells a union's branches apart, where the document
     * did not — `discriminator("Payment", property = "kind")`.
     */
    fun discriminator(schema: String, property: String) {
        discriminators.put(schema, property)
    }

    /**
     * The hosts a `$ref` may be fetched from, as origins. Set through
     * [allowRemote], so the build file reads as a statement about one host.
     */
    abstract val allowRemote: SetProperty<String>

    /**
     * Allows a `$ref` to another host — one host, on purpose, in writing.
     */
    fun allowRemote(vararg origins: String) {
        allowRemote.addAll(*origins)
    }

    /**
     * Where the URL and hash of every fetched document is recorded, defaulting
     * to `<document>.refs.lock` beside it.
     */
    abstract val lockfile: RegularFileProperty

    /**
     * The backend to generate handler stubs against, or unset for none. Stubs
     * are written once and never overwritten: after the first run they are the
     * service, not generated code.
     */
    abstract val handlers: Property<String>

    /**
     * Which codec the generated payload types are annotated for, or unset for
     * Jackson. It matters only for a `oneOf`, which becomes a sealed interface
     * carrying discriminator annotations the two libraries spell differently.
     */
    abstract val codec: Property<String>

    /**
     * The source root written into, defaulting to `build/generated/pelican/
     * <name>`. Point it at a real source root to commit the descriptions; the
     * endpoints file is rewritten on every run either way.
     */
    abstract val outputDir: DirectoryProperty

    /** What the import runs against. `pelican-import` has to be on it. */
    abstract val classpath: ConfigurableFileCollection
}

enum class DocumentFormat(internal val extension: String) {
    JSON("json"),
    YAML("yaml"),
}
