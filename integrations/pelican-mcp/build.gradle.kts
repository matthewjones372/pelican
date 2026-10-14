// A Pelican service's endpoints as MCP tool descriptions: what a model is told
// it can call, derived from the same descriptions the routes and the document
// come from.
//
// Core and `pelican-schema`, and no MCP SDK — a tool list is a value, and this
// module is the half that has to be right before anything serves it. What
// carries these over stdio or Streamable HTTP is a module of its own, so that
// wanting a tool list does not mean acquiring a server. `NoOtherDependenciesTest`
// is that claim stated as a test.
dependencies {
    api(project(":pelican-core"))
    api(project(":pelican-schema"))

    // A description is only as good as the schemas under it, and those come
    // from a codec. Jackson is the one the example uses.
    testImplementation(project(":pelican-jackson"))
}

tasks.test {
    // The main runtime classpath, so the dependency test asserts on what ships
    // rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dpelican.mcp.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
