// A build of its own rather than a module of the main one, because a plugin
// has to be built before the build that applies it can be configured. The
// root build includes it (see settings.gradle.kts there), which is what lets
// `example` apply `dev.pelican` by id and exercise the real consumer path.
rootProject.name = "pelican-gradle-plugin"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
