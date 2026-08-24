// The plugin has no dependency on any Pelican module, and that is deliberate.
//
// Generation runs against the *consumer's* classpath: their compiled spec, and
// the `pelican-codegen` / `pelican-openapi` they already depend on. The plugin
// only loads classes out of that classpath by name, so its version and the
// library's are free to move independently — and this build cannot form a
// cycle with the one it is included by.
plugins {
    kotlin("jvm") version "2.4.10"
    `java-gradle-plugin`
    // The same two the library modules are held to, with the same
    // configuration — a build of its own should not mean standards of its own.
    id("com.diffplug.spotless") version "8.10.0"
    id("dev.detekt") version "2.0.0-alpha.6"
    // The Plugin Portal is where `plugins { id(...) }` resolves from without a
    // `pluginManagement` block, so the plugin goes to both it and Central.
    id("com.gradle.plugin-publish") version "2.1.1"
    // Central, via the Portal. It wraps `maven-publish` and `signing`.
    id("com.vanniktech.maven.publish") version "0.37.0"
}

// The Maven coordinate, not the package name. `io.github.matthewjones372.pelican` would need
// pelican.dev verified on the Central Portal, and the Plugin Portal holds a
// plugin id to the same standard; this one is verified by the GitHub account
// it names. The Kotlin packages stay `io.github.matthewjones372.pelican`, which nothing checks.
group = "io.github.matthewjones372"

// One source of truth for the version: the root build's own gradle.properties.
// An included build does not inherit them, and a second copy here would be a
// second thing to forget.
version = file("../gradle.properties").readLines()
    .first { it.startsWith("pelicanVersion=") }
    .substringAfter('=')
    .trim()

kotlin { jvmToolchain(21) }

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.8.0").editorConfigOverride(mapOf("ktlint_standard_kdoc" to "disabled"))
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.8.0").editorConfigOverride(mapOf("ktlint_standard_kdoc" to "disabled"))
    }
}

detekt {
    config.setFrom(file("../config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
}

// The plain `detekt` task cannot see types; the type-resolving pair can.
tasks.named("check") { dependsOn("detektMain", "detektTest") }

gradlePlugin {
    website.set("https://github.com/matthewjones372/pelican")
    vcsUrl.set("https://github.com/matthewjones372/pelican.git")
    plugins {
        create("pelican") {
            id = "io.github.matthewjones372.pelican"
            implementationClass = "io.github.matthewjones372.pelican.gradle.PelicanPlugin"
            displayName = "Pelican"
            description = "Generates a Kotlin client and an OpenAPI document from endpoint descriptions, " +
                "and endpoint descriptions from a document."
            tags.set(listOf("openapi", "kotlin", "codegen", "pelican"))
        }
    }
}

// The plugin is exercised end to end by `example`, which applies it by id and
// generates the client this repository commits. What is tested here is the one
// part that has no compiler behind it: the names this plugin looks up on
// somebody else's classpath. The test sources stand in for the library, with
// the same class and method signatures the real modules have.
tasks.withType<Test>().configureEach { useJUnitPlatform() }

publishing {
    repositories {
        // `./gradlew -p pelican-gradle-plugin publishToMavenLocal` for a local
        // try-out, and `publishAllPublicationsToLocalRepository` for something
        // to inspect without installing it.
        maven {
            name = "local"
            url = file("../build/repo").toURI()
        }
    }
}

mavenPublishing {
    // Signed when a key is supplied and not otherwise, so a contributor can
    // build and install without one; CI supplies it. The plugin takes the same
    // switch as a gradle property, and a value set that way is already final by
    // the time this runs — which is what the second half of the condition is
    // for.
    if (providers.gradleProperty("signingInMemoryKey").isPresent &&
        !providers.gradleProperty("signAllPublications").isPresent
    ) {
        signAllPublications()
    }

    // The platform to use when `com.gradle.plugin-publish` is also applied. It
    // publishes the plugin marker as well as the jar, with sources and javadoc.
    configure(com.vanniktech.maven.publish.GradlePublishPlugin())

    pom {
        name.set("pelican-gradle-plugin")
        description.set(
            "Generates a Kotlin client and an OpenAPI document from Pelican endpoint descriptions, " +
                "and endpoint descriptions from an OpenAPI document.",
        )
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
