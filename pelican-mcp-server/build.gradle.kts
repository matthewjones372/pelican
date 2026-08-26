// The half that speaks. `pelican-mcp` derives the tools and runs a call; this
// carries them to a client — JSON-RPC 2.0 over stdio, and the request/response
// half of Streamable HTTP for a backend module to mount.
//
// No MCP SDK, and the dependency test says so. The official Kotlin SDK
// (io.modelcontextprotocol:kotlin-sdk) resolves and is current, but its server
// half is Ktor's: `Route.mcp`, `mcpStreamableHttp`, an SSE transport built on
// `ApplicationCall`, and `kotlin-sdk-server` compiles against `ktor-server-core`
// even for stdio. Adopting it would put thirty-five jars — a Ktor server, a Ktor
// client, kotlinx.serialization, kotlin-reflect, slf4j — behind `mcpServe` on a
// service that runs Pekko or http4k, and would leave the HTTP half mountable on
// one backend of the three.
dependencies {
    api(project(":pelican-mcp"))

    // A tool description is only as good as the schemas under it, and those
    // come from a codec. Jackson is the one the example uses.
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
                "-Dpelican.mcp.server.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
