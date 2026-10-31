package example.compiler

internal val nativeDecorationCallSites: Map<String, String> = mapOf(
    "nativeDecoration" to """
        import io.github.matthewjones372.pelican.AdmissionDecision
        import io.github.matthewjones372.pelican.OperationAdmission
        import io.github.matthewjones372.pelican.api
        import io.github.matthewjones372.pelican.endpoint
        import io.github.matthewjones372.pelican.jackson.JacksonCodecs
        import io.github.matthewjones372.pelican.jetty.docs.docsHandler
        import io.github.matthewjones372.pelican.jetty.handledNow
        import io.github.matthewjones372.pelican.jetty.startDecorated
        import io.github.matthewjones372.pelican.openapi.docs
        import org.eclipse.jetty.server.Handler

        val greeting = endpoint { get("greeting"); text() }
        val service = api(listOf(greeting handledNow { "hello" }), JacksonCodecs) {
            admission(OperationAdmission { AdmissionDecision.Continue })
        }
        fun serve(local: Boolean, outer: (Handler) -> Handler) = service.startDecorated(port = 0) { native ->
            outer(if (local) Handler.Sequence(native, service.docsHandler(docs { docsPath = "/api-docs" })) else native)
        }
    """,
)
