// Applied by `:benchmarks:wireBaseline` to the baseline checkout's build: writes the benchmarks' runtime classpath,
// built, to the file named by `-PwireClasspathOut`. An init script so that any ref can be the baseline.
allprojects {
    if (path == ":benchmarks") {
        afterEvaluate {
            val classpath = extensions.getByType(SourceSetContainer::class.java).getByName("main").runtimeClasspath
            val out = file(providers.gradleProperty("wireClasspathOut").get())
            tasks.register("wireClasspath") {
                dependsOn(classpath)
                doLast { out.writeText(classpath.asPath) }
            }
        }
    }
}
