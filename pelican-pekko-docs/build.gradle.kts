// The glue that serves documentation over HTTP, and the only module that needs
// both a server and a document generator. Keeping it separate is what lets a
// service depend on pelican-pekko alone: no OpenAPI code is compiled into the
// server, and none of it ships with a service that does not publish docs.
dependencies {
    api(project(":pelican-pekko"))
    api(project(":pelican-openapi"))
}
