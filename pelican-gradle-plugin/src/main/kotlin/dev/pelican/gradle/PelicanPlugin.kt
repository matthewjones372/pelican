package dev.pelican.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import java.io.File

/**
 * `plugins { id("dev.pelican") }`.
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
            task.handlers.set(endpoints.handlers)
            task.codec.set(endpoints.codec)
            task.outputDir.set(endpoints.outputDir)
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

    private fun Project.mainSourceSet(): SourceSet =
        extensions.getByType(JavaPluginExtension::class.java).sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)

    private fun File.isInside(directory: File): Boolean =
        normalize().toPath().startsWith(directory.normalize().toPath())
}
