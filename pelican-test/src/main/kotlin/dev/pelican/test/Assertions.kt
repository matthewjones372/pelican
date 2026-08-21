package dev.pelican.test

import dev.pelican.ApiError
import dev.pelican.ErrorOutput
import dev.pelican.Outcome

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
// Every other assertion here is about behaviour, and behaviour is what the
// typed call is good at: `call(getBookmark, 1L)` says nothing about the URL, so
// renaming an input breaks compilation rather than quietly starting to 404.
//
// That is also its blind spot. The client builds the request from the same
// description the server routes on, so a rename moves both at once and every
// typed test stays green — while the callers already deployed against the old
// path get a 404. The URL and the parameter names are the contract those
// callers hold; nothing in a typed call pins them.
//
// So pin them once, per endpoint, against a literal:
//
// ```
// app.request(getBookmark, 1L) shouldBuild "GET /bookmarks/1"
// ```
//
// This is the one assertion that *should* fail on a rename. `request` builds
// without sending, so it costs no server and no transport.

/**
 * Asserts the call an endpoint would send is this request line — the method,
 * the path and the query string, exactly as a caller would have to write them.
 *
 * ```
 * app.request(getBookmark, 1L) shouldBuild "GET /bookmarks/1"
 * app.request(listBookmarks, In2(20, Slug("streams"))) shouldBuild "GET /bookmarks?limit=20&tag=streams"
 * ```
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
// An endpoint that declares its failures answers with an `Outcome`, and a test
// nearly always knows which side it wants. Reaching for `when` to find out
// costs an unreachable branch and an `error("...")` in it — noise around the
// one line that is the assertion. These say it directly, and hand back the
// value so the next assertion is about the payload rather than the wrapper.

/**
 * Asserts the call succeeded, and returns the value.
 *
 * ```
 * app.outcome(getBookmark, 1L).shouldBeOk().title shouldBe "Pekko"
 * ```
 */
fun <E, T> Outcome<E, T>.shouldBeOk(): T = when (this) {
    is Outcome.Ok -> value

    is Outcome.Err -> fail(
        "Expected a successful outcome but the endpoint returned its declared " +
            "${declared.status} failure: $error",
    )
}

/**
 * Asserts the call returned one of the endpoint's declared failures, and
 * returns the payload — so the assertion about *which* failure is whatever you
 * would normally write:
 *
 * ```
 * app.outcome(getBookmark, 9_999L).shouldBeError().shouldBeInstanceOf<NoSuchBookmark>()
 * app.outcome(getBookmark, 9_999L).shouldBeError().message shouldContain "9999"
 * ```
 */
fun <E, T> Outcome<E, T>.shouldBeError(): E = when (this) {
    is Outcome.Ok -> fail("Expected a declared failure but the call succeeded with: $value")
    is Outcome.Err -> error
}

/**
 * Asserts the call failed with exactly this payload. The common case, and the
 * one worth having an infix for:
 *
 * ```
 * app.outcome(getBookmark, 9_999L) shouldBeError NoSuchBookmark(9_999L, "No bookmark 9999")
 * ```
 */
infix fun <E, T> Outcome<E, T>.shouldBeError(expected: E): E {
    val actual = shouldBeError()
    if (actual != expected) fail("Expected the declared failure $expected but was $actual")
    return actual
}

/**
 * Asserts the call failed with the failure this endpoint declared *under this
 * name* — which is the assertion to make when two failures carry the same
 * payload type under different statuses, and equality on the payload cannot
 * tell them apart.
 *
 * ```
 * app.outcome(placeOrder, input) shouldBeFailure noSuchUser
 * ```
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

// ---------------------------------------------------------------- error bodies

/**
 * The error payload, decoded. Pelican renders every framework-level failure as
 * an [ApiError], so a test can assert on its structure rather than grep the
 * body for a substring and hope.
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
