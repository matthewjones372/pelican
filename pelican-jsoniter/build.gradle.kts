// The third codec module, and the one whose library never heard of Kotlin.
// jsoniter parses and prints; the binding between JSON and a Kotlin class is
// done here, over `kotlin.reflect`, because jsoniter's own binder predates data
// classes and would construct them wrongly — see `KotlinBinding`.
dependencies {
    api(project(":pelican-core"))

    // Every dependency jsoniter declares is optional, and the two that matter —
    // javassist for its codegen modes, Jackson for its compatibility mode — are
    // not wanted here: this module stays in jsoniter's reflection mode, so the
    // artifact arrives on its own.
    api("com.jsoniter:jsoniter:0.9.23")

    // Not optional, and not a detail of this module's implementation either: a
    // payload type is read through its primary constructor at runtime, so a
    // consumer without kotlin-reflect on the classpath has no codec at all.
    api(kotlin("reflect"))

    // The agreement test generates one spec through this module and one through
    // Jackson and compares them, the same way `pelican-kotlinx` does. It lives
    // here because this is the newer of the two sides being compared.
    testImplementation(project(":pelican-openapi"))
    testImplementation(project(":pelican-jackson"))

    // And `UnionImportTest` reads a published union back into descriptions. A
    // discriminator this module invents rather than reads off an annotation is
    // only worth something if a reader holding nothing but the document can act
    // on it, and the importer is exactly that reader.
    testImplementation(project(":pelican-import"))
}
