// The Gradle plugin is a build of its own, included here rather than a module,
// because a plugin has to be built before the build applying it is configured.
// `example` applies it by id, which is the same path a consumer takes.
pluginManagement {
    includeBuild("tooling/pelican-gradle-plugin")
}

// Where a Java toolchain comes from when the machine does not already have it.
// Gradle deprecated auto-provisioning without a declared resolver, so the build
// says which one it uses rather than relying on a default that is going away.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "pelican"
// Directories are grouped by kind (spec 0078); project names are not, so
// `:pelican-jetty` and every Maven coordinate stay as they were.
fun group(dir: String, vararg names: String) = names.forEach { name ->
    include(name)
    project(":$name").projectDir = file("$dir/$name")
}

group("core", "pelican-core", "pelican-schema", "pelican-openapi")
group("codecs", "pelican-jackson", "pelican-kotlinx")
group(
    "backends/pekko",
    "pelican-pekko", "pelican-pekko-docs", "pelican-pekko-mcp",
    "pelican-test-pekko", "pelican-client-pekko", "pelican-streams",
)
group(
    "backends/jetty",
    "pelican-jetty", "pelican-jetty-docs", "pelican-jetty-mcp",
    "pelican-test-jetty", "pelican-client-jetty", "pelican-streams-jetty",
)
group("backends/gannet", "pelican-gannet", "pelican-gannet-docs")
group("testing", "pelican-test", "pelican-test-golden", "pelican-test-wiremock")
group(
    "integrations",
    "pelican-health", "pelican-health-kafka", "pelican-health-redis",
    "pelican-metrics", "pelican-metrics-otel", "pelican-oidc", "pelican-arrow",
    "pelican-mcp", "pelican-mcp-server",
)
group("tooling", "pelican-codegen", "pelican-import")
group("bom", "pelican-bom")

include(
    "example",
    // Not a library and not an example: a JMH harness, run only when asked
    // for. See benchmarks/build.gradle.kts for why it is not a source set in
    // `example`.
    "benchmarks",
)
