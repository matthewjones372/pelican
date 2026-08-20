plugins {
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
    id("com.diffplug.spotless") version "7.2.1"
    // So the root project has `check`/`build`, and the scripts formatted here
    // are covered by a plain `./gradlew build` like everything else.
    base
    `maven-publish`
    signing
}

/**
 * ktlint rather than ktfmt: ktfmt reflows, and this codebase is hand-laid-out
 * on purpose — banner comments, aligned trailing comments, KDoc written as
 * prose. The rules that make ktlint disagree with any of that are turned off
 * in `.editorconfig`, which is also where the line length lives.
 */
val ktlintVersion = "1.7.1"

// The root project builds nothing, but Spotless resolves ktlint here.
repositories { mavenCentral() }

spotless {
    kotlinGradle {
        // settings.gradle.kts and this file. Each subproject formats its own
        // build script in the `subprojects` block below.
        target("*.gradle.kts")
        ktlint(ktlintVersion)
    }
}

/** One line per module, so a Maven search result says what the artifact is. */
val moduleDescriptions = mapOf(
    "pelican-core" to "Endpoint descriptions as values. No dependencies.",
    "pelican-openapi" to "Endpoint descriptions to an OpenAPI 3.1.0 document.",
    "pelican-codegen" to "Endpoint descriptions to a Kotlin client, as source.",
    "pelican-jackson" to "Jackson codecs and swagger-core schemas for Pelican.",
    "pelican-kotlinx" to "kotlinx.serialization codecs and schemas for Pelican.",
    "pelican-pekko" to "Endpoint descriptions to a Pekko HTTP route.",
    "pelican-pekko-docs" to "Serves the OpenAPI document and Swagger UI on Pekko HTTP.",
    "pelican-http4k" to "Endpoint descriptions to an http4k HttpHandler.",
    "pelican-http4k-docs" to "Serves the OpenAPI document and Swagger UI on http4k.",
    "pelican-ktor" to "Endpoint descriptions to Ktor routes, with suspend handlers.",
    "pelican-ktor-docs" to "Serves the OpenAPI document and Swagger UI on Ktor.",
    "pelican-arrow" to "Handlers written in Arrow's Raise style, for any Pelican backend.",
    "pelican-arrow-pekko" to "Arrow Raise binders for streaming endpoints on Pekko HTTP.",
    "pelican-arrow-http4k" to "Arrow Raise binders for streaming endpoints on http4k.",
    "pelican-arrow-ktor" to "Arrow Raise binders for Ktor, where handlers suspend.",
    "pelican-test" to "A typed test client derived from the endpoint descriptions. Backend-agnostic.",
    "pelican-test-pekko" to "The in-memory transport for pelican-test, on Pekko HTTP.",
    "pelican-test-http4k" to "The in-memory transport for pelican-test, on http4k.",
)

/**
 * Every module but the example is published. The example is a runnable service
 * and a test suite; nobody should be able to depend on it by accident.
 */
val publishedModules = subprojects.map { it.name } - "example"

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    repositories { mavenCentral() }

    group = "dev.pelican"
    version = providers.gradleProperty("pelicanVersion").get()

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach { useJUnitPlatform() }

    apply(plugin = "com.diffplug.spotless")
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            targetExclude(
                // Output, not source. CI regenerates this client and runs
                // `git diff --exit-code` against the checked-in copy, so if
                // Spotless reformatted it the gate would fail for good: the
                // generator emits the unformatted text.
                "src/test/kotlin/example/generated/**/*.kt",
                // String templates that the code generator reads at runtime.
                // They are `.kt` for editor highlighting only; some are
                // fragments and do not parse standalone.
                "src/main/resources/dev/pelican/codegen/*.kt",
            )
            ktlint(ktlintVersion)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion)
        }
    }

    if (name in publishedModules) {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        // Sources and javadoc are not optional extras for a library someone
        // else has to debug — and Maven Central will not accept a release
        // without them.
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }

        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                    pom {
                        name.set(this@subprojects.name)
                        description.set(moduleDescriptions[this@subprojects.name] ?: "Part of Pelican.")
                        url.set("https://github.com/matthewjones372/pelican")
                        licenses {
                            license {
                                name.set("The Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }
                        developers {
                            developer {
                                id.set("matthewjones372")
                                name.set("Matt Jones")
                            }
                        }
                        scm {
                            url.set("https://github.com/matthewjones372/pelican")
                            connection.set("scm:git:https://github.com/matthewjones372/pelican.git")
                            developerConnection.set("scm:git:ssh://git@github.com/matthewjones372/pelican.git")
                        }
                    }
                }
            }

            repositories {
                // `./gradlew publishToMavenLocal` for a local try-out, and
                // `publishAllPublicationsToLocalRepository` for something to
                // inspect without installing it.
                maven {
                    name = "local"
                    url = rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI()
                }
            }
        }

        extensions.configure<SigningExtension> {
            // Unsigned for a local publish, signed when a key is supplied —
            // so a contributor can build and install without one.
            val key = providers.gradleProperty("signingInMemoryKey").orNull
            val password = providers.gradleProperty("signingInMemoryKeyPassword").orNull
            isRequired = key != null
            if (key != null) {
                useInMemoryPgpKeys(key, password)
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
    }
}
