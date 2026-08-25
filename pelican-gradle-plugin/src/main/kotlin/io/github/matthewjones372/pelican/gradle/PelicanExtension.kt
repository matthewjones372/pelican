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
 *
 * Containers rather than properties, because a module talking to three services
 * generates three clients, each with its own spec, package and output. Every
 * entry names its tasks: `orders` gives `generateOrdersClient`,
 * `checkOrdersClient` and `generateOrdersDocument`.
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
     *
     * `pelican-codegen` or `pelican-openapi` has to be on it, and both are read
     * by name rather than off the plugin's classpath — which is what keeps the
     * plugin's version and the library's independent.
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
     *
     * It matters for one shape only. A `oneOf` becomes a sealed interface, and
     * nothing in `sealed interface Payment` says which property carries the
     * branch or what selects each one; the two libraries spell that
     * differently. A spec without a union generates the same client either way.
     */
    abstract val codec: Property<String>

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

    /** Defaults to `build/generated/pelican/<name>/openapi.<format>`. */
    abstract val outputFile: RegularFileProperty
}

/**
 * One set of endpoint descriptions, generated *from* an OpenAPI document.
 *
 * The other entries read a compiled `ApiSpec`; this one reads a document
 * somebody else wrote, so it has no `specClass` or `specFunction` and needs
 * `pelican-import` on its classpath rather than the consumer's code.
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
     *
     * A `oneOf` with no `discriminator` stays refused: a decoder would have to
     * try each branch and keep the first that parsed, which is silently wrong
     * on the first payload two branches accept. This is the reader saying
     * which branch a payload is, once, in a file somebody reviews.
     *
     * [schema] is a component name, a pointer relative to
     * `#/components/schemas`, or a JSON pointer from the document root for a
     * union written at the endpoint — which is the case with no name to use.
     *
     * The wire value is not invented: it is the `const` a branch declares for
     * [property], or the name of the schema it points at. A branch declaring
     * neither fails the import.
     *
     * A hint that stops mattering fails the import too. An `exclude` matching
     * nothing has weakened nothing; a hint checked against nothing is a claim
     * about a payload format nobody is verifying.
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
     *
     * A remote `$ref` is refused by default because a build that fetches a URL
     * produces different code on a different day and fails offline. This moves
     * that decision to somebody who can make it, once per host, and pairs it
     * with a [lockfile] that records and checks the hash of what came back.
     *
     * An entry is an origin and not a URL prefix, because a prefix match is how
     * an allowlist is got past: `https://good.example` is a prefix of
     * `https://good.example.evil.test`. A bare `example.com` means https, so
     * plain HTTP has to be written out and shows up in a review.
     *
     * Redirects are never followed: a host that can redirect can move the
     * document somewhere nobody reviewed. The failure names the URL it gave.
     */
    fun allowRemote(vararg origins: String) {
        allowRemote.addAll(*origins)
    }

    /**
     * Where the URL and hash of every fetched document is recorded, defaulting
     * to `<document>.refs.lock` beside it.
     *
     * Commit it: it is what makes a fetching build reproducible, since a
     * document changing behind one of those URLs then fails the build rather
     * than generating different code. `update<Name>EndpointsLock` rewrites it,
     * and refuses to change a hash it holds without `--accept-changes`.
     *
     * The documents themselves are cached in a `.d` directory beside it.
     * Committing that too is the difference between a build that needs the
     * network and one that does not.
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
