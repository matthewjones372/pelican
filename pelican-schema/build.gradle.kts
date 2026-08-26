// A schema that resolves on its own, for a consumer with no OpenAPI document to
// point `#/components/schemas/` at. Core only, beside `pelican-openapi` rather
// than inside it: wanting one type described should not mean acquiring a
// document generator.
//
// The codec modules are test-scoped because the claim worth making spans them —
// a schema source has to hand back a document whose every `$ref` resolves — and
// one module is where that can be asserted once per source rather than in each.
dependencies {
    api(project(":pelican-core"))

    // A validator that did not write the schema, on the principle that already
    // puts swagger-parser in front of the emitted document.
    testImplementation("com.networknt:json-schema-validator:1.5.9")

    testImplementation(project(":pelican-jackson"))
}

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load — the
    // codecs being test-scoped, it loads a good deal more.
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
