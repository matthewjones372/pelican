plugins { kotlin("plugin.serialization") }

// A schema that resolves on its own, for a consumer with no OpenAPI document to
// point `#/components/schemas/` at. Core only, beside `pelican-openapi` rather
// than inside it: wanting one type described should not mean acquiring a
// document generator.
//
// The three codec modules are test-scoped because the claim worth making spans
// them — three schema sources that agree on almost nothing else have to hand
// back a document whose every `$ref` resolves — and one module is where that
// can be asserted once instead of three times.
dependencies {
    api(project(":pelican-core"))

    testImplementation(project(":pelican-jackson"))
    testImplementation(project(":pelican-kotlinx"))
    testImplementation(project(":pelican-jsoniter"))
}

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load — the
    // three codecs being test-scoped, it loads a good deal more.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dpelican.schema.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
