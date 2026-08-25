// Golden files for the two things a description publishes: the OpenAPI
// document, and the bytes a call puts on the wire.
//
// A typed test asserts on behaviour, and behaviour is the half of the contract
// that survives a rename — `call(getOrder, 1L)` keeps passing after the path
// moves, while every caller already deployed against the old path gets a 404.
// These snapshots pin the other half, in a file a reviewer reads in the diff.
//
// Separate from `pelican-test` because it needs `pelican-openapi`, and a suite
// that only wants a typed client should not have the document generator on its
// classpath to get one.
dependencies {
    api(project(":pelican-test"))
    api(project(":pelican-openapi"))

    testImplementation(project(":pelican-jackson"))
}
