package io.github.matthewjones372.pelican.spi

import io.github.matthewjones372.pelican.AdmissionDecision
import io.github.matthewjones372.pelican.AdmissionRequest
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.OperationAdmission

/** Keeps backend request views behind core's admission boundary. */
fun OperationAdmission.check(endpoint: Endpoint<*, *>, view: RequestView): AdmissionDecision =
    check(AdmissionRequest(endpoint, view::headers))
