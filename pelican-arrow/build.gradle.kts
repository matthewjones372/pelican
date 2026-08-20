// Arrow's Raise DSL as a way to write Pelican handlers. Nothing here knows
// which server will run them: an endpoint that declares failures with `orFail`
// is an `Endpoint<I, Fallible<E, T>>` whatever the backend, and binding one to
// a value-returning handler names no Pekko, http4k or Ktor type. Streams do
// name one, so those binders live in pelican-arrow-pekko, -http4k and -ktor.
//
// arrow-core and pelican-core, and deliberately nothing else — this module is
// a translation layer, not a place to start depending on a JSON library.
val arrowVersion = "2.2.3"

dependencies {
    api(project(":pelican-core"))
    api("io.arrow-kt:arrow-core:$arrowVersion")
}

// A handler wants two things in scope: the `Params` receiver every Pelican
// binder gives it, and the `Raise<E>` that makes `raise(...)` work. Kotlin has
// one receiver slot, so the second arrives as a context parameter — which is
// still behind this flag in 2.2. Callers writing such a lambda need the flag
// too; that is the price of the syntax and it is stated in the KDoc.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xcontext-parameters")
}
