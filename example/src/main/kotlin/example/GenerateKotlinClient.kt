package example

import dev.pelican.codegen.writeKotlinClient
import java.io.File

/**
 * The client half. The same [ordersSpec] the OpenAPI document is generated
 * from, read as Kotlin source: no server is started and nothing is called.
 *
 * The generator writes into the source root it is handed, under the directories
 * the package name implies, so regenerating is this task and nothing else. The
 * example points it at its own test sources, which is what keeps
 * `example.generated.OrdersClient` compiled and exercised on every build.
 *
 * The hidden endpoint is absent from the generated file for the same reason it
 * is absent from the document — `writeKotlinClient(includeHidden = true)` is
 * there for an internal client that is meant to know about it.
 */
fun main(args: Array<String>) {
    val sourceRoot = File(args.firstOrNull() ?: "src/test/kotlin")
    val written = ordersSpec().writeKotlinClient(sourceRoot, packageName = "example.generated")
    println("Wrote $written")
}
