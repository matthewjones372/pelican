// A build of its own rather than a module of the main one, because a plugin
// has to be built before the build that applies it can be configured. The
// root build includes it (see settings.gradle.kts there), which is what lets
// `example` apply `dev.pelican` by id and exercise the real consumer path.

// This build provisions its own toolchain, so it declares its own resolver;
// settings of an included build do not inherit the including build's.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "pelican-gradle-plugin"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
