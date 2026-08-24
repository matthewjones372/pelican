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
    // The same tag this repository's other build reads. An included build is
    // still the same working tree, so both land on the same version.
    id("pl.allegro.tech.build.axion-release") version "1.21.3"
}

scmVersion {
    // An included build does not look above its own directory for `.git`, and
    // axion answers with its default version rather than failing when it finds
    // none — which would have published the plugin as 0.1.0-SNAPSHOT off a
    // release tag. Point it at the repository both builds actually live in.
    repository { directory.set(file("..").absolutePath) }
    tag { prefix.set("v") }
    // Plain `0.1.0-SNAPSHOT` off a tag rather than axion's default, which
    // decorates it with the branch name. The README tells a contributor to
    // install locally and depend on the version; that version should not
    // change with the branch they happen to be on.
    versionCreator("simple")
}

// The namespace verified on the Central Portal. The Plugin Portal holds a
// plugin id to the same standard, so the id below shares the prefix rather
// than claiming one this account cannot prove it owns.
group = "io.github.matthewjones372"

version = scmVersion.version

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
