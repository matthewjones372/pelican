package io.github.matthewjones372.pelican.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import java.io.File

/**
 * `plugins { id("io.github.matthewjones372.pelican") }`.
 */
class PelicanPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val pelican = project.extensions.create("pelican", PelicanExtension::class.java)

        // The default for every entry. It carries its own task dependencies,
        // so a generate task compiles what it is about to load.
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
            // Beside the document rather than under `build/`: a checked-in
            // input, reviewed in the same diff as the `$ref` that made it
            // necessary, and one `clean` would otherwise delete.
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
        // rather than the provider — hence `afterEvaluate`.
        afterEvaluate {
            val generated = client.outputDir.get().asFile
            val ours = generated.isInside(layout.buildDirectory.get().asFile)

            generate.configure { task ->
                task.cleanOutput.set(ours)
                if (ours) {
                    // A directory this task owns is a tracked output.
                    task.outputs.dir(client.outputDir)
                } else {
                    // A source root is not: declaring it would make every
                    // compile depend on this task, and a committed file is
                    // regenerated deliberately.
                    task.outputs.upToDateWhen { false }
                }
            }

            // Nothing inside `build/` can drift, so only a committed client
            // gets the check.
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

        // Registered whether or not a host is allowed: a task nothing depends
        // on never runs, and somebody who has just written `allowRemote(...)`
        // finds the lockfile task by asking `tasks`.
        tasks.register("update${name}EndpointsLock", UpdateEndpointsLockTask::class.java) { task ->
            task.description = "Records the URL and hash of every remote \$ref the ${endpoints.name} document reaches"
            task.classpath.from(endpoints.classpath)
            task.document.set(endpoints.document)
            task.lockfile.set(endpoints.lockfile)
            task.allowRemote.set(endpoints.allowRemote)
            task.entryName.set(endpoints.name)
        }

        // As the client task: inside `build/` is this task's own output,
        // anywhere else is a committed source root.
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
     * the lockfile. Spelled here as well as in the library, which is cheaper
     * than an entry point existing only to answer a question whose answer is
     * a suffix.
     */
    private fun cacheOf(lockfile: File) = File(lockfile.parentFile, lockfile.name + ".d")

    private fun Project.mainSourceSet(): SourceSet =
        extensions.getByType(JavaPluginExtension::class.java).sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)

    private fun File.isInside(directory: File): Boolean =
        normalize().toPath().startsWith(directory.normalize().toPath())
}
