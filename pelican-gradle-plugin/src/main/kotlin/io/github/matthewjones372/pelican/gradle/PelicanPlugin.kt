package io.github.matthewjones372.pelican.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import java.io.File

/**
 * `plugins { id("io.github.matthewjones372.pelican") }`.
 *
 * The endpoint descriptions are the source of truth for the service, for its
 * documentation and for its client; this is what makes the last two a build
 * task rather than a `main` somebody wrote and a `JavaExec` somebody wired.
 *
 * Nothing here compiles against Pelican. Generation runs against the
 * consumer's own classpath — see [Pelican] — so the plugin version and the
 * library version are independent.
 */
class PelicanPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val pelican = project.extensions.create("pelican", PelicanExtension::class.java)

        // `main`'s runtime classpath, if there is one, as the default for every
        // entry. It carries its own task dependencies, so a generate task
        // compiles what it is about to load without anybody saying `dependsOn`.
        val defaultClasspath = project.objects.fileCollection()
        project.plugins.withType(JavaPlugin::class.java).configureEach {
            defaultClasspath.from(project.mainSourceSet().runtimeClasspath)
        }

        pelican.clients.configureEach { client ->
            client.specFunction.convention("spec")
            client.includeHidden.convention(false)
            client.outputDir.convention(project.defaultOutput(client.name))
            client.classpath.from(defaultClasspath)
        }

        pelican.documents.configureEach { document ->
            document.specFunction.convention("spec")
            document.format.convention(DocumentFormat.JSON)
            document.outputFile.convention(
                document.format.flatMap { format ->
                    project.defaultOutput(document.name).map { it.file("openapi.${format.extension}") }
                },
            )
            document.classpath.from(defaultClasspath)
        }

        pelican.endpoints.configureEach { endpoints ->
            endpoints.outputDir.convention(project.defaultOutput(endpoints.name))
            endpoints.classpath.from(defaultClasspath)
            // Beside the document rather than under `build/`: it is a checked-in
            // input, it is reviewed in the same diff as the `$ref` that made it
            // necessary, and a lockfile inside the build directory would be a
            // lockfile `clean` deletes.
            endpoints.lockfile.convention(
                endpoints.document.map { document ->
                    val file = document.asFile
                    project.layout.projectDirectory.file(
                        File(file.parentFile, "${file.name.substringBeforeLast('.')}.refs.lock").absolutePath,
                    )
                },
            )
        }

        pelican.clients.all { client -> project.register(client) }
        pelican.documents.all { document -> project.register(document) }
        pelican.endpoints.all { endpoints -> project.register(endpoints) }
    }

    // ----------------------------------------------------------------------

    private fun Project.register(client: ClientSpec) {
        val name = client.name.replaceFirstChar { it.uppercase() }

        val generate = tasks.register("generate${name}Client", GenerateKotlinClientTask::class.java) { task ->
            task.description = "Generates the ${client.name} Kotlin client from the endpoint descriptions"
            task.classpath.from(client.classpath)
            task.specClass.set(client.specClass)
            task.specFunction.set(client.specFunction)
            task.packageName.set(client.packageName)
            task.clientName.set(client.clientName)
            task.baseUrl.set(client.baseUrl)
            task.includeHidden.set(client.includeHidden)
            task.codec.set(client.codec)
            task.outputDir.set(client.outputDir)
        }

        val check = tasks.register("check${name}Client", CheckKotlinClientTask::class.java) { task ->
            task.description = "Fails when the committed ${client.name} client is not what the descriptions generate"
            task.classpath.from(client.classpath)
            task.specClass.set(client.specClass)
            task.specFunction.set(client.specFunction)
            task.packageName.set(client.packageName)
            task.clientName.set(client.clientName)
            task.baseUrl.set(client.baseUrl)
            task.includeHidden.set(client.includeHidden)
            task.codec.set(client.codec)
            task.outputDir.set(client.outputDir)
        }

        // Where the output lands decides two things, and both need the value
        // rather than the provider — hence `afterEvaluate`, which is the last
        // moment the build script can still have changed it.
        afterEvaluate {
            val generated = client.outputDir.get().asFile
            val ours = generated.isInside(layout.buildDirectory.get().asFile)

            generate.configure { task ->
                task.cleanOutput.set(ours)
                if (ours) {
                    // A directory this task owns is a tracked output: up to
                    // date when nothing changed, and cleaned when it did.
                    task.outputs.dir(client.outputDir)
                } else {
                    // A source root is not. Declaring it would make every task
                    // that compiles those sources depend on this one, which is
                    // not what checking generated code into a repository means:
                    // the file is committed, and regenerating it is deliberate.
                    task.outputs.upToDateWhen { false }
                }
            }

            // Nothing can drift out of date inside `build/`, so the check is
            // only wired up for a client somebody commits.
            if (!ours) {
                tasks.matching { task -> task.name == "check" }.configureEach { task -> task.dependsOn(check) }
            }
        }
    }

    private fun Project.register(endpoints: EndpointsSpec) {
        val name = endpoints.name.replaceFirstChar { it.uppercase() }

        val generate = tasks.register("generate${name}Endpoints", GenerateEndpointsTask::class.java) { task ->
            task.description = "Generates the ${endpoints.name} endpoint descriptions from an OpenAPI document"
            task.classpath.from(endpoints.classpath)
            task.document.set(endpoints.document)
            task.packageName.set(endpoints.packageName)
            task.entryName.set(endpoints.name)
            task.exclude.set(endpoints.exclude)
            task.discriminators.set(endpoints.discriminators)
            task.allowRemote.set(endpoints.allowRemote)
            task.lockfile.set(endpoints.lockfile)
            task.remoteInputs.from(endpoints.lockfile, endpoints.lockfile.map { cacheOf(it.asFile) })
            task.handlers.set(endpoints.handlers)
            task.codec.set(endpoints.codec)
            task.outputDir.set(endpoints.outputDir)
        }

        // Registered whether or not a host is allowed, and it costs nothing to:
        // a task nothing depends on is configured lazily and never runs. What
        // it buys is that somebody who has just written `allowRemote(...)` finds
        // the task that fills the lockfile by asking `tasks`, rather than by
        // reading the failure and then the manual.
        tasks.register("update${name}EndpointsLock", UpdateEndpointsLockTask::class.java) { task ->
            task.description = "Records the URL and hash of every remote \$ref the ${endpoints.name} document reaches"
            task.classpath.from(endpoints.classpath)
            task.document.set(endpoints.document)
            task.lockfile.set(endpoints.lockfile)
            task.allowRemote.set(endpoints.allowRemote)
            task.entryName.set(endpoints.name)
        }

        // The same rule the client task follows: a directory inside `build/`
        // is this task's own, and anywhere else is a source root somebody
        // commits — where a tracked output would make every compile depend on
        // regenerating.
        afterEvaluate {
            val ours = endpoints.outputDir.get().asFile.isInside(layout.buildDirectory.get().asFile)
            generate.configure { task ->
                task.cleanOutput.set(ours)
                if (ours) {
                    task.outputs.dir(endpoints.outputDir)
                } else {
                    task.outputs.upToDateWhen { false }
                }
            }
        }
    }

    private fun Project.register(document: DocumentSpec) {
        val name = document.name.replaceFirstChar { it.uppercase() }

        val generate = tasks.register("generate${name}Document", GenerateDocumentTask::class.java) { task ->
            task.description = "Writes the ${document.name} OpenAPI document from the endpoint descriptions"
            task.classpath.from(document.classpath)
            task.specClass.set(document.specClass)
            task.specFunction.set(document.specFunction)
            task.format.set(document.format)
            task.outputFile.set(document.outputFile)
        }

        afterEvaluate {
            val target = document.outputFile.get().asFile
            generate.configure { task ->
                if (target.isInside(layout.buildDirectory.get().asFile)) {
                    task.outputs.file(document.outputFile)
                } else {
                    task.outputs.upToDateWhen { false }
                }
            }
        }
    }

    private fun Project.defaultOutput(name: String) = layout.buildDirectory.dir("generated/pelican/$name")

    /**
     * Where `pelican-import` caches what it fetched: a `.d` directory beside
     * the lockfile.
     *
     * The rule is spelled here as well as in the library, and one line of
     * duplication is the cheaper half of the trade. The alternative was an
     * entry point on `pelican-import` existing only to be asked where its own
     * cache is — which is a signature to keep compatible forever, for a
     * question whose answer is a suffix.
     */
    private fun cacheOf(lockfile: File) = File(lockfile.parentFile, lockfile.name + ".d")

    private fun Project.mainSourceSet(): SourceSet =
        extensions.getByType(JavaPluginExtension::class.java).sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)

    private fun File.isInside(directory: File): Boolean =
        normalize().toPath().startsWith(directory.normalize().toPath())
}
