plugins { kotlin("plugin.serialization") }

// The alternative codec module. It exists so that "the JSON library is
// pluggable" is a fact with a test behind it rather than a claim: the same
// endpoint descriptions are documented and served through this too.
dependencies {
    api(project(":pelican-core"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(project(":pelican-openapi"))
    testImplementation(project(":pelican-jackson"))

    // UnionRoundTripTest publishes a sealed hierarchy and reads the document
    // back. It needs the importer for the same reason it needs the models to
    // be @Serializable: the round trip is only a round trip if both halves are
    // present, and this is the module where the kotlinx half can exist.
    testImplementation(project(":pelican-import"))

    // And GeneratedClientUnionTest generates a client for a kotlinx service and
    // decodes the payload its declarations describe. Same reason again: a
    // generated hierarchy is only readable if something reads it, and the
    // reader has to sit beside a compiled @Serializable twin.
    testImplementation(project(":pelican-codegen"))
}
