// The Gradle plugin is a build of its own, included here rather than a module,
// because a plugin has to be built before the build applying it is configured.
// `example` applies it by id, which is the same path a consumer takes.
pluginManagement {
    includeBuild("pelican-gradle-plugin")
}

rootProject.name = "pelican"
include(
    "pelican-core",
    "pelican-openapi",
    "pelican-codegen",
    "pelican-jackson",
    "pelican-kotlinx",
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
