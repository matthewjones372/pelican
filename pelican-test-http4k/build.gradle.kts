// The http4k half of the test support.
//
// There is almost nothing to it: http4k interprets an `Api` into an
// `HttpHandler`, which *is* a function from request to response, so the
// in-memory transport is that function with `RequestSpec` translated on the way
// in and `ResponseSpec` on the way out.
//
// It exists so that "every suite runs twice, in memory and over a socket" is
// true of this backend too, rather than only of Pekko.
dependencies {
    api(project(":pelican-test"))
    api(project(":pelican-http4k"))

    testImplementation(project(":pelican-jackson"))
}
