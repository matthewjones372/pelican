package dev.surefold.pelican.spi

import dev.surefold.pelican.AdmissionDecision
import dev.surefold.pelican.AdmissionRequest
import dev.surefold.pelican.Endpoint
import dev.surefold.pelican.OperationAdmission

/** Keeps backend request views behind core's admission boundary. */
fun OperationAdmission.check(endpoint: Endpoint<*, *>, view: RequestView): AdmissionDecision =
    check(AdmissionRequest(endpoint, view::headers))
