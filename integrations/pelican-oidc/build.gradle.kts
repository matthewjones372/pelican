// Verifies an OpenID Connect provider's tokens for a Pelican caller (spec
// 0061). Nimbus does the JOSE work, here and nowhere else, so pelican-core's
// runtime classpath stays the Kotlin standard library and jackson-core.
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
    testImplementation("org.apache.pekko:pekko-http_2.13:1.4.1")
    testImplementation("com.microsoft.playwright:playwright:1.59.0")
}

// SignInTest's Chromium, fetched ahead of the tests. Left to the test, a slow mirror spends the test's five-minute
// timeout on the download and fails a build that changed nothing here; CI runs this first and caches what it fetched.
tasks.register<JavaExec>("installChromium") {
    description = "Downloads the Chromium build this Playwright expects, unless it is already there."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    args("install", "chromium")
}

// Playwright's driver otherwise fetches Firefox and WebKit as well on its first `create()`, which the test never
// launches and which is the download that ran out the clock. Chromium comes from the task above, so it is there.
tasks.named<Test>("test") {
    dependsOn("installChromium")
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
}
