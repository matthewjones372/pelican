val okhttpVersion = "4.12.0"

// The fourth adapter: core's `ClientTransport` over OkHttp's `Call`. Core plus
// OkHttp and nothing else, which is what `DependenciesTest` asserts.
//
// This is the one an Android app wants, since `java.net.http` is not there and
// OkHttp is what the platform already ships — but the module is plain JVM, with
// no AndroidX and no Android plugin. Running on Android is a consequence of
// depending on nothing that does not, not a target of its own.
//
// 4.x rather than 5.x: 4.12.0 is the floor a consumer has to meet, and Gradle
// resolves upwards, so a build already on OkHttp 5 keeps it. A fleet pinned to
// OkHttp 3 has no adapter here and keeps the Ktor route —
// `KtorHttpTransport(HttpClient(OkHttp))` — which is documented in
// docs/generated-client.md.
dependencies {
    api(project(":pelican-core"))
    api("com.squareup.okhttp3:okhttp:$okhttpVersion")

    // Nothing extra for the tests. They send at the JDK's own `HttpServer`,
    // which proves more about what goes on the wire than OkHttp's own
    // `MockWebServer` answering the client half of the same library would.
}

tasks.test {
    // The main runtime classpath, so DependenciesTest can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dpelican.client.okhttp.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
