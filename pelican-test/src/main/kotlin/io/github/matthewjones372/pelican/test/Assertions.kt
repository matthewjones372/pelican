package io.github.matthewjones372.pelican.test

import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.ErrorOutput
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.Output

/*
 * Assertions for the things every endpoint test asserts on.
 *
 * The point is the failure message. `assertEquals(404, res.status)` reports
 * "expected: <404> but was: <500>" and leaves you to go and find the body that
 * would explain why; these print it, because a wrong status is nearly always
 * explained by the payload that came with it.
 *
 * They throw plain `AssertionError`, which every JUnit, kotest and Kotlin-test
 * runner already understands. This module used to export kotest's matchers as
 * an `api` dependency, which put kotest on the classpath of anyone who wanted
 * a typed client and had their own matchers already. The ones that return a
 * value — `shouldBeOk`, `shouldBeError` — are the join: assert the shape here,
 * then carry on with whatever matcher library you actually use.
 */

// ------------------------------------------------------------------- requests
//
// A typed call says nothing about the URL, which is its strength and its blind
// spot: a rename moves the client and the server together, so every typed test
// stays green while deployed callers get a 404.
//
// So pin the request line once per endpoint against a literal. This is the one
// assertion that should fail on a rename, and `request` builds without sending.

/**
 * Asserts the call an endpoint would send is this request line — method, path
 * and query string, as a caller would have to write them.
 */
infix fun RequestSpec.shouldBuild(expected: String): RequestSpec = apply {
    val actual = toString()
    if (actual != expected) {
        fail(
            "Expected the call to build `$expected` but it built `$actual`. " +
                "The difference is what every caller already pointed at the old " +
                "one would get back as a 404.",
        )
    }
}

// ------------------------------------------------------------------ responses

infix fun ResponseSpec.shouldHaveStatus(expected: Int): ResponseSpec = apply {
    if (status != expected) {
        fail("Expected status $expected but was $status. Body:\n${body.take(500)}")
    }
}

fun ResponseSpec.shouldBeSuccessful(): ResponseSpec = apply {
    if (!isSuccess) fail("Expected a 2xx but was $status. Body:\n${body.take(500)}")
}

infix fun ResponseSpec.shouldHaveContentType(expected: String): ResponseSpec = apply {
    if (contentType != expected) fail("Expected content type '$expected' but was '$contentType'")
}

fun ResponseSpec.shouldHaveHeader(name: String, expected: String): ResponseSpec = apply {
    val actual = header(name)
    if (actual != expected) fail("Expected header '$name: $expected' but was '$actual'")
}

fun ResponseSpec.shouldHaveNoBody(): ResponseSpec = apply {
    if (body.isNotEmpty()) fail("Expected an empty body but got ${body.length} chars:\n${body.take(200)}")
}

// -------------------------------------------------------------------- outcomes
//
// A test nearly always knows which side of an `Outcome` it wants, and a `when`
// to find out costs an unreachable branch. These say it directly and hand back
// the value, so the next assertion is about the payload.

/** Asserts the call succeeded, and returns the value. */
fun <E, T> Outcome<E, T>.shouldBeOk(): T = when (this) {
    is Outcome.Ok -> value

    is Outcome.Err -> fail(
        "Expected a successful outcome but the endpoint returned its declared " +
            "${declared.status} failure: $error",
    )
}

/**
 * Asserts the call returned one of the declared failures and returns the
 * payload, so the assertion about which one is whatever you normally write.
 */
fun <E, T> Outcome<E, T>.shouldBeError(): E = when (this) {
    is Outcome.Ok -> fail("Expected a declared failure but the call succeeded with: $value")
    is Outcome.Err -> error
}

/** Asserts the call failed with exactly this payload. */
infix fun <E, T> Outcome<E, T>.shouldBeError(expected: E): E {
    val actual = shouldBeError()
    if (actual != expected) fail("Expected the declared failure $expected but was $actual")
    return actual
}

/**
 * Asserts the call failed with the failure declared under this name — the
 * assertion to make when two failures share a payload type under different
 * statuses and equality cannot tell them apart.
 */
infix fun <E, T> Outcome<E, T>.shouldBeFailure(expected: ErrorOutput<out E>): E = when (this) {
    is Outcome.Ok -> fail("Expected the declared failure $expected but the call succeeded with: $value")

    is Outcome.Err ->
        if (declared === expected) error
        else fail(
            "Expected the failure declared as $expected but the handler returned " +
                "the one declared as $declared, carrying: $error",
        )
}

/**
 * Asserts the call came back as the success declared under this name, and
 * returns the value — for an endpoint declaring more than one 2xx, where a
 * `200 Order` and a `201 Order` cannot be told apart by equality.
 */
infix fun <E, T> Outcome<E, T>.shouldBeResponse(expected: Output<*>): T = when (this) {
    is Outcome.Err -> fail(
        "Expected the response declared as $expected but the endpoint returned its declared " +
            "${declared.status} failure: $error",
    )

    is Outcome.Ok ->
        if (declared === expected) value
        else fail(
            "Expected the response declared as $expected but the handler returned " +
                "the one declared as ${declared ?: "(none)"}, carrying: $value",
        )
}

// ---------------------------------------------------------------- error bodies

/**
 * The error payload, decoded. Every framework-level failure renders as an
 * [ApiError], so a test asserts on structure rather than grepping the body.
 */
fun ApiClient.errorBody(response: ResponseSpec): ApiError = decodeBody(response)

/** Asserts the response is an [ApiError] with this status and message. */
fun ApiClient.shouldBeApiError(response: ResponseSpec, status: Int, error: String): ApiError {
    response shouldHaveStatus status
    response shouldHaveContentType "application/json"
    val body = errorBody(response)
    if (body.status != status || body.error != error) {
        fail("Expected ApiError(status=$status, error=\"$error\") but was $body")
    }
    return body
}

private fun fail(message: String): Nothing = throw AssertionError(message)
