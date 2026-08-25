package io.github.matthewjones372.pelican.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * What every generating task needs: the classpath to run against.
 *
 * The classpath is the task's real input. Declaring it `@Classpath` is what
 * makes the tasks incremental in the way that matters — an unrelated change to
 * a resource does not regenerate anything, and a change to the file the spec
 * is written in regenerates everything.
 */
@DisableCachingByDefault(because = "Generation is one reflective call; a cache lookup would cost more")
abstract class PelicanTask : DefaultTask() {

    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:Inject
    abstract val workers: WorkerExecutor

    init {
        group = "pelican"
    }

    /**
     * Classloader isolation rather than a forked process: the work is one
     * reflective call into code that is already compiled, and a JVM per
     * generated client would cost more than the generation does. The consumer's
     * classpath is loaded in isolation from Gradle's own, so their Jackson and
     * Gradle's cannot be the same Jackson.
     */
    protected fun queue() = workers.classLoaderIsolation { it.classpath.from(classpath) }
}

/**
 * A task that reads the descriptions themselves: a class to load and a
 * function to call, which between them return the `ApiSpec`.
 *
 * The import task is the one generating task that is not one of these. It
 * reads a document rather than compiled descriptions, so there is no spec for
 * it to load — which is the whole difference between the two directions,
 * stated as a class.
 */
@DisableCachingByDefault(because = "See PelicanTask")
abstract class SpecTask : PelicanTask() {

    @get:Input
    abstract val specClass: Property<String>

    @get:Input
    abstract val specFunction: Property<String>
}

/**
 * Writes one client. See `ClientSpec` for what each property means.
 *
 * Not cacheable, and not because it could not be: where the output lands is a
 * decision the build script makes, and a client written into a source root is
 * a file somebody edits, commits and reviews. A cache hit that silently
 * replaced it would be answering a question nobody asked.
 */
@DisableCachingByDefault(because = "The output may be a source root the consumer owns")
abstract class GenerateKotlinClientTask : SpecTask() {

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    @get:Optional
    abstract val clientName: Property<String>

    @get:Input
    @get:Optional
    abstract val baseUrl: Property<String>

    @get:Input
    abstract val includeHidden: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val codec: Property<String>

    /**
     * Not `@OutputDirectory` here. Whether the directory is an output Gradle
     * tracks is decided when the task is registered, because pointing this at
     * a source root — a supported choice — would otherwise make every task
     * that compiles those sources depend on this one. See `PelicanPlugin`.
     */
    @get:Internal
    abstract val outputDir: DirectoryProperty

    /** True when [outputDir] is inside the build directory and ours to empty. */
    @get:Internal
    abstract val cleanOutput: Property<Boolean>

    @TaskAction
    fun generate() {
        val target = outputDir.get().asFile
        // A renamed client must not leave the old one behind — but only where
        // the whole directory belongs to this task. A source root has the
        // consumer's own files in it.
        if (cleanOutput.get()) target.deleteRecursively()
        target.mkdirs()

        queue().submit(GenerateClientWork::class.java) {
            it.specClass.set(specClass)
            it.specFunction.set(specFunction)
            it.packageName.set(packageName)
            it.clientName.set(clientName)
            it.baseUrl.set(baseUrl)
            it.includeHidden.set(includeHidden)
            it.codec.set(codec)
            it.outputDir.set(target)
        }
    }
}

/**
 * Fails when a committed client no longer matches the descriptions.
 *
 * Only worth having where the client is checked in, which is why the plugin
 * wires it into `check` for those entries and leaves it alone for the rest.
 */
@UntrackedTask(because = "Compares a committed file against freshly generated output")
abstract class CheckKotlinClientTask : SpecTask() {

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    @get:Optional
    abstract val clientName: Property<String>

    @get:Input
    @get:Optional
    abstract val baseUrl: Property<String>

    @get:Input
    abstract val includeHidden: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val codec: Property<String>

    /**
     * Read, never written — the generated copy goes to a temporary directory
     * and the two are compared there. The task declares no outputs and so
     * always runs, which for a comparison is the only honest answer: what it
     * is checking is a file somebody edits by hand.
     */
    @get:Internal
    abstract val outputDir: DirectoryProperty

    init {
        group = "verification"
    }

    @TaskAction
    fun check() {
        queue().submit(CheckClientWork::class.java) {
            it.specClass.set(specClass)
            it.specFunction.set(specFunction)
            it.packageName.set(packageName)
            it.clientName.set(clientName)
            it.baseUrl.set(baseUrl)
            it.includeHidden.set(includeHidden)
            it.codec.set(codec)
            it.outputDir.set(outputDir)
        }
    }
}

/**
 * Writes endpoint descriptions read from an OpenAPI document.
 *
 * Not cacheable and never up to date for the same reason the client task is
 * not: where the output lands is the build script's decision, and it is
 * commonly a source root somebody commits.
 */
@DisableCachingByDefault(because = "The output may be a source root the consumer owns; see the client task")
abstract class GenerateEndpointsTask : PelicanTask() {

    // The contents are the input and where the file sits is not: the same
    // document imported from two checkouts is the same import.
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val document: RegularFileProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val exclude: SetProperty<String>

    /** Schema -> the property that tells its `oneOf` branches apart. See `EndpointsSpec.discriminator`. */
    @get:Input
    abstract val discriminators: MapProperty<String, String>

    /** The hosts a `$ref` may be fetched from. See `EndpointsSpec.allowRemote`. */
    @get:Input
    abstract val allowRemote: SetProperty<String>

    /** `@Internal` because the lockfile need not exist; [remoteInputs] is what Gradle snapshots. */
    @get:Internal
    abstract val lockfile: RegularFileProperty

    /**
     * The lockfile and the cache beside it, as inputs.
     *
     * A file collection rather than `@InputFile`, because neither is there
     * until a host is allowed and the update task has run — and an
     * `@InputFile` that is missing fails the build before the task can say
     * anything useful about why. They are still declared: what the import
     * generates depends on the bytes those hold, and a task that read them
     * without saying so would report up to date over a lockfile somebody had
     * just edited.
     */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val remoteInputs: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val handlers: Property<String>

    @get:Input
    @get:Optional
    abstract val codec: Property<String>

    /** The name the generated file and its `<name>Spec()` function are built from. */
    @get:Input
    abstract val entryName: Property<String>

    /** `@Internal` for the same reason the client's output is; see there. */
    @get:Internal
    abstract val outputDir: DirectoryProperty

    /** True when [outputDir] is inside the build directory and ours to empty. */
    @get:Internal
    abstract val cleanOutput: Property<Boolean>

    @TaskAction
    fun generate() {
        // Handler stubs are written once and never overwritten, which is what
        // makes them a starting point rather than output. Inside `build/` there
        // is nothing to protect — nobody edits a file there — and leaving last
        // run's stubs in place would leave them describing an older document.
        if (cleanOutput.get()) outputDir.get().asFile.deleteRecursively()

        queue().submit(GenerateEndpointsWork::class.java) {
            it.document.set(document)
            it.packageName.set(packageName)
            it.entryName.set(entryName)
            it.exclude.set(exclude)
            it.discriminators.set(discriminators)
            it.allowRemote.set(allowRemote)
            it.lockfile.set(lockfile)
            it.handlers.set(handlers)
            it.codec.set(codec)
            it.outputDir.set(outputDir)
        }
    }
}

/**
 * Rewrites the lockfile of remote `$ref`s from what the allowed hosts are
 * serving now.
 *
 * The one task here that trusts the network, and it is a task of its own for
 * exactly that reason: nothing depends on it, `build` does not reach it, and
 * it has to be typed. "Just re-run the update task" is how a hash check gets
 * neutered, so a hash that is already recorded will not change without
 * `--accept-changes` as well — adding a URL nobody had locked is free and
 * shows in the diff, and *changing* one is the event the lockfile exists for.
 */
@UntrackedTask(because = "It fetches: what the far end says now is the answer, by definition")
abstract class UpdateEndpointsLockTask : PelicanTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val document: RegularFileProperty

    @get:Internal
    abstract val lockfile: RegularFileProperty

    @get:Input
    abstract val allowRemote: SetProperty<String>

    /** The name the failures spell the entry and this task with. */
    @get:Input
    abstract val entryName: Property<String>

    @get:Internal
    abstract val acceptChanges: Property<Boolean>

    @Option(
        option = "accept-changes",
        description = "Record a new hash for a URL already in the lockfile. Read what changed first.",
    )
    fun acceptChanges() {
        acceptChanges.set(true)
    }

    @TaskAction
    fun update() {
        queue().submit(UpdateLockWork::class.java) {
            it.document.set(document)
            it.lockfile.set(lockfile)
            it.allowRemote.set(allowRemote)
            it.entryName.set(entryName)
            it.acceptChanges.set(acceptChanges.getOrElse(false))
        }
    }
}

/** Writes one OpenAPI document, in JSON or YAML. */
@DisableCachingByDefault(because = "The output may be a path the consumer owns; see the client task")
abstract class GenerateDocumentTask : SpecTask() {

    @get:Input
    abstract val format: Property<DocumentFormat>

    /** `@Internal` for the same reason the client's output is; see there. */
    @get:Internal
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        queue().submit(GenerateDocumentWork::class.java) {
            it.specClass.set(specClass)
            it.specFunction.set(specFunction)
            it.format.set(format)
            it.outputFile.set(outputFile)
        }
    }
}

/**
 * Fails when the descriptions have moved away from a document callers hold.
 *
 * Registered for a `documents` entry that names a `baseline`, and wired into
 * `check` — which is where a question about somebody else's deployed client
 * belongs, next to the compiler and the tests rather than in a release ritual
 * somebody has to remember.
 */
@UntrackedTask(because = "Compares a committed document against freshly generated output")
abstract class CheckDocumentTask : SpecTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseline: RegularFileProperty

    /** The name the failure spells the entry with. */
    @get:Input
    abstract val entryName: Property<String>

    init {
        group = "verification"
    }

    @TaskAction
    fun check() {
        queue().submit(CheckDocumentWork::class.java) {
            it.specClass.set(specClass)
            it.specFunction.set(specFunction)
            it.baseline.set(baseline)
            it.entryName.set(entryName)
        }
    }
}
