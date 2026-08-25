plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    id("com.diffplug.spotless") version "8.10.0"
    id("dev.detekt") version "2.0.0-alpha.6" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    // The version comes from the nearest `v` tag rather than a property, so
    // cutting a release is `git tag v0.1.0 && git push --tags` and nothing
    // else. An untagged commit is a -SNAPSHOT of the next one.
    id("pl.allegro.tech.build.axion-release") version "1.21.3"
    // So the root project has `check`/`build`, and the scripts formatted here
    // are covered by a plain `./gradlew build` like everything else.
    base
    // Publishing to the Central Portal, which is the only way in since OSSRH
    // closed. It wraps `maven-publish` and `signing`, so neither is applied
    // here directly.
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    // Renders the KDoc into the javadoc jar the published modules ship.
    id("org.jetbrains.dokka") version "2.1.0" apply false
}

scmVersion {
    tag { prefix.set("v") }
    // Plain `0.1.0-SNAPSHOT` off a tag rather than axion's default, which
    // decorates it with the branch name. The README tells a contributor to
    // install locally and depend on the version; that version should not
    // change with the branch they happen to be on.
    versionCreator("simple")
}

// Read once, at configuration time: `scmVersion.version` shells out to git.
val scmVer: String = scmVersion.version

/**
 * ktlint rather than ktfmt: ktfmt reflows, and this codebase is hand-laid-out
 * on purpose — banner comments, aligned trailing comments, KDoc written as
 * prose. The rules that make ktlint disagree with any of that are turned off
 * in `.editorconfig`, which is also where the line length lives.
 */
val ktlintVersion = "1.8.0"

// Spotless does not pick these up from .editorconfig for every source set, so
// they are handed to the ktlint step directly. The reasoning for each lives in
// .editorconfig beside the rest, which is also what the IDE reads.
val ktlintOverrides = mapOf("ktlint_standard_kdoc" to "disabled")

// The root project builds nothing, but Spotless resolves ktlint here.
repositories { mavenCentral() }

spotless {
    kotlinGradle {
        // settings.gradle.kts and this file. Each subproject formats its own
        // build script in the `subprojects` block below.
        target("*.gradle.kts")
        ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
    }
}

/** One line per module, so a Maven search result says what the artifact is. */
val moduleDescriptions = mapOf(
    "pelican-core" to "Endpoint descriptions as values. No dependencies.",
    "pelican-openapi" to "Endpoint descriptions to an OpenAPI 3.1.0 or 3.2.0 document.",
    "pelican-schema" to "A type to a self-contained JSON Schema 2020-12 document.",
    "pelican-mcp" to "Endpoint descriptions to MCP tool descriptions.",
    "pelican-codegen" to "Endpoint descriptions to a Kotlin client, as source.",
    "pelican-import" to "An OpenAPI document to endpoint descriptions, as source.",
    "pelican-jackson" to "Jackson codecs and swagger-core schemas for Pelican.",
    "pelican-kotlinx" to "kotlinx.serialization codecs and schemas for Pelican.",
    "pelican-jsoniter" to "jsoniter codecs and reflection schemas for Pelican.",
    "pelican-pekko" to "Endpoint descriptions to a Pekko HTTP route.",
    "pelican-pekko-docs" to "Serves the OpenAPI document and Swagger UI on Pekko HTTP.",
    "pelican-http4k" to "Endpoint descriptions to an http4k HttpHandler.",
    "pelican-http4k-docs" to "Serves the OpenAPI document and Swagger UI on http4k.",
    "pelican-ktor" to "Endpoint descriptions to Ktor routes, with suspend handlers.",
    "pelican-ktor-docs" to "Serves the OpenAPI document and Swagger UI on Ktor.",
    "pelican-metrics" to "Micrometer meters for a Pelican service, dimensioned by the descriptions.",
    "pelican-metrics-otel" to "OpenTelemetry spans and metrics for a Pelican service, from the descriptions.",
    "pelican-client-java" to "A generated client's transport, over the JDK's own HttpClient.",
    "pelican-client-pekko" to "A generated client's transport, over Pekko HTTP's client.",
    "pelican-client-ktor" to "A generated client's transport, over Ktor's HttpClient.",
    "pelican-test" to "A typed test client derived from the endpoint descriptions. Backend-agnostic.",
    "pelican-test-golden" to "Golden files per endpoint that fail on a change breaking existing callers.",
    "pelican-test-pekko" to "The in-memory transport for pelican-test, on Pekko HTTP.",
    "pelican-test-http4k" to "The in-memory transport for pelican-test, on http4k.",
)

// Coverage, aggregated across the modules rather than per-module: a line in
// `pelican-core` is exercised by the tests in `pelican-pekko` as often as by
// its own, and a per-module number would report that as a gap.
//
// The floor is deliberately below where the number sits now. It is a ratchet
// against regression, not a target to code towards — a test written to move a
// percentage is worth less than no test at all.
kover {
    reports {
        total {
            verify {
                rule {
                    minBound(80)
                }
            }
            filters {
                excludes {
                    // Generated output, checked in and regenerated by a task.
                    classes("example.generated.*")
                }
            }
        }
    }
}

dependencies {
    // Every module but `benchmarks`. Its classes are run by JMH in a forked
    // JVM, never by a test, so they would arrive here as several hundred lines
    // that no test covers and pull the total down — a coverage floor that
    // falls because a benchmark was written is measuring the wrong thing.
    // See benchmarks/build.gradle.kts for why the agent stays off it entirely.
    subprojects.filter { it.name != "benchmarks" }.forEach { kover(project(it.path)) }
}

// A floor nobody runs is not a floor: `./gradlew build` checks it.
tasks.named("check") {
    dependsOn("koverVerify")
    // The Gradle plugin is a build of its own — included, so `example` can
    // apply it by id — and a build of its own is not checked by this one
    // unless it is asked for.
    dependsOn(gradle.includedBuild("pelican-gradle-plugin").task(":check"))
}

/**
 * Every module but the example and the benchmarks is published. The example is
 * a runnable service and a test suite, and the benchmarks are a JMH harness;
 * nobody should be able to depend on either by accident.
 */
val publishedModules = subprojects.map { it.name } - setOf("example", "benchmarks")

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    repositories { mavenCentral() }

    // The namespace verified on the Central Portal, against the GitHub
    // account it names.
    group = "io.github.matthewjones372"
    version = scmVer

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
        // Assertions only. The tests still run on the JUnit platform — kotest
        // is here for its matchers and for the failure messages they produce,
        // not as a second test framework.
        "testImplementation"("io.kotest:kotest-assertions-core:6.2.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()

        systemProperty("junit.jupiter.execution.timeout.default", "60s")
    }

    apply(plugin = "org.jetbrains.kotlinx.kover")

    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        // The deviations live in one file for the whole build; see its header.
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
    }
    // The plain `detekt` task cannot see types, so the rules that need them —
    // ForbiddenMethodCall, for one — are silently skipped there. `check`
    // depends on the type-resolving pair instead.
    tasks.named("check") { dependsOn("detektMain", "detektTest") }
    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        // Generated output, checked in and regenerated by a task — the same
        // exclusion Spotless makes, for the same reason.
        exclude("**/example/generated/**")
    }

    apply(plugin = "com.diffplug.spotless")
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            targetExclude(
                "src/test/kotlin/example/generated/**/*.kt",
                // String templates the generators read at runtime. They are
                // `.kt` for editor highlighting only; some are fragments, and
                // some carry placeholders that do not parse standalone.
                "src/main/resources/io/github/matthewjones372/pelican/*/*.kt",
            )
            ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
        }
    }

    if (name in publishedModules) {
        apply(plugin = "com.vanniktech.maven.publish")
        apply(plugin = "org.jetbrains.dokka")

        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            // Sources are not an optional extra for a library someone else has
            // to debug, and Maven Central will not accept a release without a
            // javadoc jar. Dokka fills it: an empty jar leaves javadoc.io
            // blank, which puts the KDoc out of reach of anyone who has not
            // cloned the repository.
            configure(
                com.vanniktech.maven.publish.KotlinJvm(
                    javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
                    sourcesJar = true,
                ),
            )

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

        extensions.configure<PublishingExtension> {
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
    }
}
