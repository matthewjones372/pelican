import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// Kover's coverage reporter resolves `freemarker` 2.3.32, which carries an
// advisory. Dependabot cannot act on it -- no manifest here names the
// coordinate, so its security job ends `security_update_dependency_not_found`
// -- so it is pinned here rather than ignored.
buildscript {
    dependencies {
        constraints {
            classpath("org.freemarker:freemarker:2.3.35")
        }
    }
}

plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    id("com.diffplug.spotless") version "8.10.4"
    id("dev.detekt") version "2.0.0-alpha.6" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
    // The version comes from the nearest `v` tag rather than a property, so
    // cutting a release is `git tag v0.1.0 && git push --tags` and nothing
    // else. An untagged commit is a -SNAPSHOT of the next one.
    id("pl.allegro.tech.build.axion-release") version "1.21.4"
    // So the root project has `check`/`build`, and the scripts formatted here
    // are covered by a plain `./gradlew build` like everything else.
    base
    // Publishing to the Central Portal, which is the only way in since OSSRH
    // closed. It wraps `maven-publish` and `signing`, so neither is applied
    // here directly.
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    // Renders the KDoc into the javadoc jar the published modules ship.
    // What each published module's binary surface is, as a file somebody reads
    // in a diff. The golden-file argument, applied to the Kotlin API instead of
    // to the HTTP one.
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.2"
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

spotless {
    kotlinGradle {
        // settings.gradle.kts, this file and the BOM's, which applies no
        // Spotless of its own. Each other subproject formats its own build
        // script in the `subprojects` block below.
        target("*.gradle.kts", "bom/*/build.gradle.kts")
        ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
    }
}

/** One line per module, so a Maven search result says what the artifact is. */
val moduleDescriptions = mapOf(
    "pelican-core" to "Endpoint descriptions as values. Depends only on jackson-core.",
    "pelican-openapi" to "Endpoint descriptions to an OpenAPI 3.1.0 or 3.2.0 document.",
    "pelican-schema" to "A type to a self-contained JSON Schema 2020-12 document.",
    "pelican-mcp" to "Endpoint descriptions to MCP tool descriptions.",
    "pelican-mcp-server" to "Serves those tools: MCP over stdio and Streamable HTTP, no SDK.",
    "pelican-codegen" to "Endpoint descriptions to a Kotlin client, as source.",
    "pelican-import" to "An OpenAPI document to endpoint descriptions, as source.",
    "pelican-jackson" to "Jackson codecs and swagger-core schemas for Pelican.",
    "pelican-kotlinx" to "kotlinx.serialization codecs and schemas for Pelican.",
    "pelican-arrow" to "Arrow's Either into Pelican's Outcome and back.",
    "pelican-pekko" to "Endpoint descriptions to a Pekko HTTP route.",
    "pelican-pekko-docs" to "Serves the OpenAPI document and Swagger UI on Pekko HTTP.",
    "pelican-pekko-mcp" to "Serves the MCP tools over Streamable HTTP on Pekko HTTP.",
    "pelican-jetty" to "Endpoint descriptions to a Jetty 12 handler.",
    "pelican-jetty-docs" to "Serves the OpenAPI document and Swagger UI on Jetty.",
    "pelican-jetty-mcp" to "Serves the MCP tools over Streamable HTTP on Jetty.",
    "pelican-metrics" to "Micrometer meters for a Pelican service, dimensioned by the descriptions.",
    "pelican-metrics-otel" to "OpenTelemetry spans and metrics for a Pelican service, from the descriptions.",
    "pelican-health" to "Live and ready probes for a Pelican service, answered in application/health+json.",
    "pelican-health-kafka" to "A Pelican health check over the Kafka Admin client a service already holds.",
    "pelican-health-redis" to "A Pelican health check over the Lettuce connection a service already holds.",
    "pelican-oidc" to "Verifies OpenID Connect tokens for a Pelican caller.",
    "pelican-client-jetty" to "A generated client's transport, over Jetty's HttpClient.",
    "pelican-client-pekko" to "A generated client's transport, over Pekko HTTP's client.",
    "pelican-streams" to "A streaming body as a surefold Stream, whose failure is a value.",
    "pelican-streams-jetty" to "A streaming body on Jetty as a surefold Stream, whose failure is a value.",
    "pelican-gannet" to "Endpoint descriptions served on Gannet, an HTTP/1.1 server on io_uring.",
    "pelican-gannet-docs" to "Serves the OpenAPI document and Swagger UI on Gannet.",
    "pelican-test" to "A typed test client derived from the endpoint descriptions. Backend-agnostic.",
    "pelican-test-golden" to "Golden files per endpoint that fail on a change breaking existing callers.",
    "pelican-test-pekko" to "The in-memory transport for pelican-test, on Pekko HTTP.",
    "pelican-test-jetty" to "The in-memory transport for pelican-test, on Jetty.",
    "pelican-test-gannet" to "A loopback transport for pelican-test, on Gannet.",
    "pelican-test-wiremock" to "WireMock stubbed and verified in endpoint values, and stub files exported from them.",
    "pelican-bom" to "Every Pelican module at one version, so a build names the version once.",
)

// Coverage, aggregated across the modules rather than per-module: a line in
// `pelican-core` is exercised by the tests in `pelican-pekko` as often as by
// its own, and a per-module number would report that as a gap.
//
// The floor is deliberately below where the number sits now. It is a ratchet
// against regression, not a target to code towards — a test written to move a
// percentage is worth less than no test at all.
//
// It moved from 80 to 90 once the denominator stopped counting `main` functions
// and the three places worth covering were covered: the security builders a
// caller ran first, the classifier that decides whether a change is breaking,
// and the in-memory transport nothing exercised. Ninety rather than the 91.9 it
// actually sits at, so a release that legitimately adds a hard-to-reach branch
// does not have to move this line in the same commit.
apiValidation {
    // The first two are not published, and the BOM has no classes.
    ignoredProjects += listOf("example", "benchmarks", "pelican-bom")
}

kover {
    reports {
        total {
            verify {
                rule {
                    minBound(90)
                }
            }
            filters {
                excludes {
                    // Generated output, checked in and regenerated by a task.
                    classes("example.generated.*")

                    // A `main` that binds a port and prints a URL. There is no
                    // behaviour in one to assert, and mocking the socket out
                    // would assert less than nothing — the same reasoning that
                    // already excludes the generated client above, which is why
                    // these sit beside it rather than in a list of their own.
                    classes("example.MainKt")
                    classes("example.*.MainKt")
                    classes("example.readme.*")
                    classes("example.filters.*")
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
    //
    // The Gannet modules count only where their tests must run. They skip wherever io_uring is unusable -- off Linux,
    // and on a Linux whose seccomp profile or `io_uring_disabled` refuses `io_uring_setup`, as CI's container does --
    // and skipped lines would pull the total down. Being on Linux says nothing about the second, so the signal is
    // `PELICAN_REQUIRE_URING=1`, which makes those tests fail rather than skip (spec 0088).
    val gannetModules = setOf("pelican-gannet", "pelican-gannet-docs", "pelican-test-gannet")
    val gannetRuns = System.getenv("PELICAN_REQUIRE_URING") == "1"
    subprojects
        .filter { it.name !in setOf("benchmarks", "pelican-bom") }
        .filter { gannetRuns || it.name !in gannetModules }
        .forEach { kover(project(it.path)) }
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
 * Every module but the example, the benchmarks and the Gannet backend is
 * published. The example is a runnable service and a test suite, and the
 * benchmarks are a JMH harness; nobody should be able to depend on either by
 * accident. The Gannet modules are experimental and on main only: they depend
 * on a Gannet snapshot, which a release must not.
 */
val publishedModules = subprojects.map { it.name } -
    setOf("example", "benchmarks", "pelican-gannet", "pelican-gannet-docs", "pelican-test-gannet")

// What `pelican-bom` constrains, so a new module is in it without an edit there.
extra["libraryModules"] = publishedModules - "pelican-bom"

val floor25 = setOf(
    "pelican-streams", "pelican-streams-jetty",
    "pelican-gannet", "pelican-gannet-docs", "pelican-test-gannet",
    "example", "benchmarks",
)

subprojects {
    // The namespace verified on the Central Portal, against the GitHub
    // account it names.
    group = "io.github.matthewjones372"
    version = scmVer

    if (name in publishedModules) publishWithPom()
    // A `java-platform` has no sources, so none of what follows applies to it.
    if (name == "pelican-bom") return@subprojects

    // surefold-stream is class file 69, so a module built on it is Java 25 until surefold targets 21. Gannet needs FFM
    // and the JDK's internal continuation, and whatever depends on it is 25 too: the example's parity suites and the
    // benchmarks run every backend (spec 0088).
    val jvmFloor = if (name in floor25) 25 else 21

    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        // 21, the LTS most services run. Below 24 a virtual thread blocked in `synchronized` pins its carrier, which
        // `Handlers.default` answers with platform threads (spec 0079). The streams modules need what surefold needs.
        jvmToolchain(jvmFloor)

        compilerOptions {
            // Without this, every interface with a method body also gets a
            // `DefaultImpls` class holding a copy of it, and both are published
            // surface the BCV gate then has to keep. The interfaces here
            // already emit real JVM default methods, so the copies are the ABI
            // of a compatibility mode with nothing behind it: Kotlin callers
            // never reach them, and the Java caller they exist for would have
            // had to write `JsonValue.DefaultImpls.x(this)` by hand.
            jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
        }
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
        // Assertions only. The tests still run on the JUnit platform — kotest
        // is here for its matchers and for the failure messages they produce,
        // not as a second test framework.
        "testImplementation"("io.kotest:kotest-assertions-core:6.2.6")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    // BCV 0.18.1 reads classes with ASM 9.6, which stops at class file 68 (JDK 24). 9.10.1 reads 25's.
    configurations.matching { it.name.startsWith("bcv-rt-jvm-cp") }.configureEach {
        resolutionStrategy.force("org.ow2.asm:asm:9.11", "org.ow2.asm:asm-tree:9.11")
    }

    // The Kotlin plugin resolves Bouncy Castle 1.84 here, which has a critical
    // name-constraints bypass. 1.86 is what the plugin classpath already picks.
    configurations.matching { it.name == "kotlinBouncyCastleConfiguration" }.configureEach {
        resolutionStrategy.force(
            "org.bouncycastle:bcprov-jdk18on:1.86",
            "org.bouncycastle:bcpg-jdk18on:1.86",
            "org.bouncycastle:bcpkix-jdk18on:1.86",
            "org.bouncycastle:bcutil-jdk18on:1.86",
        )
    }

    val toolchains = extensions.getByType<JavaToolchainService>()

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()

        // `jvmToolchain` above sets the Java toolchain as well as the Kotlin
        // one, and a `Test` task with no launcher of its own takes it. So
        // every job in the CI matrix would compile *and run* on the toolchain,
        // and a matrix of runtimes would exercise one. The tests run on
        // whichever JDK started the build instead, but never below the
        // bytecode they have to load.
        val testJdk = maxOf(JavaVersion.current().majorVersion.toInt(), jvmFloor)
        javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(testJdk)) })

        systemProperty("pelican.launcherJavaVersion", testJdk.toString())
        // Whether the Gannet tests may skip. An input, so a cached run that skipped them is not reused for one
        // that must run them.
        inputs.property("pelicanRequireUring", System.getenv("PELICAN_REQUIRE_URING").orEmpty())
        // `-PjettyPool=virtual` runs every suite's Jetty servers on `VirtualThreadPool` (spec 0085).
        providers.gradleProperty("jettyPool").orNull?.let { systemProperty("pelican.jetty.pool", it) }
        // A backstop, not a budget: it exists so one wedged test cannot burn
        // the whole job, and it fires on nothing else. The slowest measured
        // method is `DoesNotCompileTest > a failure of the same payload type
        // compiles, whichever end` at 18.8s, so five minutes is sixteen times
        // the worst real case — a tighter number would be a wall-clock
        // assertion that a slow runner fails for no reason. See spec 0050.
        systemProperty("junit.jupiter.execution.timeout.default", "5m")

        // A Scala `case object` thrown by a test serialises through
        // `scala.runtime.ModuleSerializationProxy`, which the daemon cannot
        // load because it carries no scala-library. Gradle then reports a
        // `TestFailureSerializationException` naming the real type in its
        // *message* — and `SHORT`, the default, prints types and never
        // messages. See spec 0051.
        testLogging { exceptionFormat = TestExceptionFormat.FULL }
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
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            // Sources are not an optional extra for a library someone else has
            // to debug. Maven Central will not accept a release without a javadoc
            // jar, and an empty one satisfies it; the KDoc reads from sources.
            configure(
                com.vanniktech.maven.publish.KotlinJvm(
                    javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
                    sourcesJar = true,
                ),
            )
        }
    }
}

/** POM metadata and the local inspection repository, shared by the libraries and the BOM. */
fun Project.publishWithPom() {
    apply(plugin = "com.vanniktech.maven.publish")
    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        pom {
            name.set(this@publishWithPom.name)
            description.set(moduleDescriptions[this@publishWithPom.name] ?: "Part of Pelican.")
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
