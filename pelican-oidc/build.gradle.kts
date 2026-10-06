// Verifies an OpenID Connect provider's tokens for a Pelican caller (spec
// 0061). Nimbus does the JOSE work, here and nowhere else, so pelican-core's
// runtime classpath stays the Kotlin standard library.
//
// Nimbus arrives as `implementation`: no Nimbus type is in this module's public
// signatures, so a consumer never has to name it.
dependencies {
    api(project(":pelican-core"))
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")

    testImplementation(project(":pelican-test"))
    testImplementation(project(":pelican-jackson"))
    // Signing in is a browser following redirects, so it is tested in one: a
    // Pekko server, a stub provider, and Chromium from PLAYWRIGHT_BROWSERS_PATH.
    testImplementation(project(":pelican-pekko"))
    // Compiled against there, not shipped, so the test brings its own.
    testImplementation(platform("org.apache.pekko:pekko-bom_2.13:1.7.1"))
    testImplementation("org.apache.pekko:pekko-actor-typed_2.13")
    testImplementation("org.apache.pekko:pekko-stream_2.13")
    testImplementation("org.apache.pekko:pekko-http_2.13:1.4.0")
    testImplementation("com.microsoft.playwright:playwright:1.59.0")
}
