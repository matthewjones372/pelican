// The default codec module. Jackson reads the bodies; swagger-core describes
// the types, reading the same Jackson annotations Jackson itself reads, so the
// document and the wire format cannot drift apart.
dependencies {
    api(project(":pelican-core"))

    // Not optional. Without it Kotlin data-class defaults and nullability are
    // invisible to Jackson: a missing field becomes an NPE deep inside a
    // constructor call rather than a clean 400.
    api("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.2")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.2")

    implementation("io.swagger.core.v3:swagger-core-jakarta:2.2.54")
}
