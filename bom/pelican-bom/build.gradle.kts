plugins {
    `java-platform`
}

@Suppress("UNCHECKED_CAST")
val libraryModules = rootProject.extra["libraryModules"] as List<String>

dependencies {
    constraints {
        libraryModules.forEach { api(project(":$it")) }
    }
}

mavenPublishing {
    configure(com.vanniktech.maven.publish.JavaPlatform())
}
