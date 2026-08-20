plugins { kotlin("plugin.serialization") }

// The alternative codec module. It exists so that "the JSON library is
// pluggable" is a fact with a test behind it rather than a claim: the same
// endpoint descriptions are documented and served through this too.
dependencies {
    api(project(":pelican-core"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // CodecAgreementTest generates one spec through each codec module and
    // compares them. It lives here because this is the module whose reason to
    // exist is that comparison, and because the models it needs are
    // @Serializable — which only this module's compiler plugin can produce.
    testImplementation(project(":pelican-openapi"))
    testImplementation(project(":pelican-jackson"))
}
