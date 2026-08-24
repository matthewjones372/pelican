// The other direction: an OpenAPI document in, endpoint descriptions out, as
// Kotlin source.
//
// Every other module in this repository reads endpoint values and writes
// something else. This one is the only reader of a document somebody else
// wrote, which is why it is the only module with a parser in it. That parser
// is snakeyaml-engine and nothing more: YAML 1.2 is a superset of JSON, so one
// dependency reads both, and the tree it produces is turned straight into
// core's own `JsonValue` — the same type `pelican-openapi` writes out.
//
// It depends on `pelican-codegen` for the half of the job the two share:
// turning a JSON Schema into Kotlin declarations. A client generated from a
// document and a client generated from endpoint values should not disagree
// about what `Order` looks like, and the way to guarantee that is for both to
// be the same code.
//
// Nothing here is needed at runtime by a service. It is a build-time tool,
// reached through the Gradle plugin's `generate<Name>Endpoints` task.
dependencies {
    api(project(":pelican-codegen"))
    implementation("org.snakeyaml:snakeyaml-engine:2.10")
}
