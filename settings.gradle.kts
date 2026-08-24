// The Gradle plugin is a build of its own, included here rather than a module,
// because a plugin has to be built before the build applying it is configured.
// `example` applies it by id, which is the same path a consumer takes.
pluginManagement {
    includeBuild("pelican-gradle-plugin")
}

// Where a Java toolchain comes from when the machine does not already have it.
// Gradle deprecated auto-provisioning without a declared resolver, so the build
// says which one it uses rather than relying on a default that is going away.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "pelican"
include(
    "pelican-core",
    "pelican-openapi",
    "pelican-codegen",
    "pelican-import",
    "pelican-jackson",
    "pelican-kotlinx",
    "pelican-jsoniter",
    "pelican-pekko",
    "pelican-pekko-docs",
    "pelican-http4k",
    "pelican-http4k-docs",
    "pelican-ktor",
    "pelican-ktor-docs",
    "pelican-test",
    "pelican-test-pekko",
    "pelican-test-http4k",
    "example",
)
