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
}
