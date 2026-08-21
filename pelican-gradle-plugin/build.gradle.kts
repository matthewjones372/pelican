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
    `maven-publish`
    signing
}

group = "dev.pelican"

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
            id = "dev.pelican"
            implementationClass = "dev.pelican.gradle.PelicanPlugin"
            displayName = "Pelican"
            description = "Generates a Kotlin client and an OpenAPI document from Pelican endpoint descriptions."
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
        maven {
            name = "local"
            url = file("../build/repo").toURI()
        }
    }
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing.publications.withType<MavenPublication>().configureEach {
    pom {
        name.set("pelican-gradle-plugin")
        description.set("Generates a Kotlin client and an OpenAPI document from Pelican endpoint descriptions.")
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

signing {
    // Unsigned for a local publish, signed when a key is supplied — the same
    // arrangement the library modules have.
    val key = providers.gradleProperty("signingInMemoryKey").orNull
    val password = providers.gradleProperty("signingInMemoryKeyPassword").orNull
    isRequired = key != null
    if (key != null) {
        useInMemoryPgpKeys(key, password)
        sign(publishing.publications)
    }
}
