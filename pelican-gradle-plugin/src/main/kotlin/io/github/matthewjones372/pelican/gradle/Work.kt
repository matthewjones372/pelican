package io.github.matthewjones372.pelican.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File
import java.nio.file.Files

/**
 * The work itself, run against the consumer's classpath rather than the
 * plugin's. The action's own classloader is theirs, so loading by name from
 * here finds their classes and their library version.
 */
internal interface SpecParameters : WorkParameters {
    val specClass: Property<String>
    val specFunction: Property<String>
}

internal interface ClientParameters : SpecParameters {
    val packageName: Property<String>
    val clientName: Property<String>
    val baseUrl: Property<String>
    val includeHidden: Property<Boolean>
    val codec: Property<String>
    val outputDir: DirectoryProperty
}

internal interface EndpointsParameters : WorkParameters {
    val document: RegularFileProperty
    val packageName: Property<String>
    val entryName: Property<String>
    val exclude: SetProperty<String>
    val discriminators: MapProperty<String, String>
    val allowRemote: SetProperty<String>
    val lockfile: RegularFileProperty
    val handlers: Property<String>
    val codec: Property<String>
    val outputDir: DirectoryProperty
}

internal interface LockParameters : WorkParameters {
    val document: RegularFileProperty
    val lockfile: RegularFileProperty
    val allowRemote: SetProperty<String>
    val entryName: Property<String>
    val acceptChanges: Property<Boolean>
}

internal interface DocumentParameters : SpecParameters {
    val format: Property<DocumentFormat>
    val outputFile: RegularFileProperty
}

internal abstract class GenerateClientWork : WorkAction<ClientParameters> {
    override fun execute() {
        val written = parameters.writeClientInto(parameters.outputDir.get().asFile)
        logger.lifecycle("Wrote $written")
    }
}

/**
 * Generates into a scratch directory and compares, checking that a committed
 * client still says what the descriptions say. The failure names the task that
 * fixes it.
 */
internal abstract class CheckClientWork : WorkAction<ClientParameters> {
    override fun execute() {
        val scratch = Files.createTempDirectory("pelican-check").toFile()
        try {
            val expected = parameters.writeClientInto(scratch)
            val actual = parameters.outputDir.get().asFile.resolve(expected.relativeTo(scratch).path)
            when {
                !actual.isFile -> throw PelicanFailure("$actual is missing. Run the matching generate task.")

                actual.readText() != expected.readText() ->
                    throw PelicanFailure(
                        "$actual is not what the endpoint descriptions generate. " +
                            "Run the matching generate task and commit the result.",
                    )
            }
        } finally {
            scratch.deleteRecursively()
        }
    }
}

internal abstract class GenerateEndpointsWork : WorkAction<EndpointsParameters> {
    override fun execute() {
        val written = Pelican.writeEndpoints(
            javaClass.classLoader,
            parameters.document.get().asFile,
            parameters.outputDir.get().asFile,
            parameters.packageName.get(),
            parameters.entryName.get(),
            parameters.exclude.get(),
            parameters.handlers.orNull,
            parameters.codec.orNull,
            parameters.discriminators.get(),
            parameters.allowRemote.get(),
            parameters.lockfile.asFile.orNull,
        )
        written.forEach { logger.lifecycle("Wrote $it") }
    }
}

/**
 * What the update task ran, said out loud. Every line rather than a count,
 * because the reader is deciding whether to commit it and "3 references
 * updated" is not reviewable.
 */
internal abstract class UpdateLockWork : WorkAction<LockParameters> {
    override fun execute() {
        Pelican.updateLock(
            javaClass.classLoader,
            parameters.document.get().asFile,
            parameters.lockfile.get().asFile,
            parameters.entryName.get(),
            parameters.allowRemote.get(),
            parameters.acceptChanges.get(),
        ).forEach { line -> logger.lifecycle(line.toString()) }
    }
}

internal abstract class GenerateDocumentWork : WorkAction<DocumentParameters> {
    override fun execute() {
        val spec = Pelican.spec(javaClass.classLoader, parameters.specClass.get(), parameters.specFunction.get())
        val target = parameters.outputFile.get().asFile
        target.parentFile?.mkdirs()
        target.writeText(Pelican.document(javaClass.classLoader, spec, parameters.format.get()))
        logger.lifecycle("Wrote $target")
    }
}

private fun ClientParameters.writeClientInto(sourceRoot: File): File {
    val loader = GenerateClientWork::class.java.classLoader
    val spec = Pelican.spec(loader, specClass.get(), specFunction.get())
    return Pelican.writeClient(
        loader,
        spec,
        sourceRoot,
        packageName.get(),
        clientName.orNull,
        baseUrl.orNull,
        includeHidden.get(),
        codec.orNull,
    )
}

private val logger = org.gradle.api.logging.Logging.getLogger("pelican")
