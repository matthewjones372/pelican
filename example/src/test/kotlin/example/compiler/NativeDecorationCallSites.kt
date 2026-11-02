package example.compiler

internal val nativeDecorationCallSites: Map<String, String> = mapOf(
    "nativeDecoration" to """
        import dev.surefold.pelican.AdmissionDecision
        import dev.surefold.pelican.OperationAdmission
        import dev.surefold.pelican.api
        import dev.surefold.pelican.endpoint
        import dev.surefold.pelican.jackson.JacksonCodecs
        import dev.surefold.pelican.jetty.docs.docsHandler
        import dev.surefold.pelican.jetty.handledNow
        import dev.surefold.pelican.jetty.startDecorated
        import dev.surefold.pelican.openapi.docs
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
