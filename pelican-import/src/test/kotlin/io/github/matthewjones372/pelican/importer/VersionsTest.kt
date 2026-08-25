package io.github.matthewjones372.pelican.importer

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

class VersionsTest {

    private val options = ImportOptions("app", "tiny")

    private val fromV2 = imported(File("src/test/resources/tiny-2.0.json"), options)
    private val fromV3 = imported(File("src/test/resources/tiny-3.0.yaml"), options)

    @Test
    fun `a 2 0 document and its 3 0 twin generate the same descriptions`() {
        fromV2 shouldBe fromV3
    }

    @Test
    fun `2 0's host, basePath and schemes are the server a 3 x document states outright`() {
        fromV2 shouldContain """servers = listOf("https://tiny.example.com/v1")"""
    }

    @Test
    fun `2 0 writes a parameter's type on the parameter, and it still arrives typed`() {
        fromV2 shouldContain """val limit = queryParam("limit", IntCodec.atLeast(1)).optional()"""
    }

    @Test
    fun `2 0's body parameter is a request body`() {
        fromV2 shouldContain "val createWidgetBody = jsonBody<Widget>()"
    }

    @Test
    fun `2 0's basic auth is http auth`() {
        val document = documentOf(
            "openapi.json" to """
                {
                  "swagger": "2.0",
                  "info": { "title": "T", "version": "1" },
                  "security": [{ "login": [] }],
                  "securityDefinitions": { "login": { "type": "basic" } },
                  "paths": {
                    "/x": { "get": { "operationId": "x", "responses": { "204": { "description": "ok" } } } }
                  }
                }
            """,
        )
        imported(document) shouldContain """val login = basicAuth(name = "login")"""
    }

    @Test
    fun `2 0's oauth2 flow names are the ones 3 x renamed them to`() {
        val document = documentOf(
            "openapi.json" to """
                {
                  "swagger": "2.0",
                  "info": { "title": "T", "version": "1" },
                  "security": [{ "oauth": ["read"] }],
                  "securityDefinitions": {
                    "oauth": {
                      "type": "oauth2",
                      "flow": "accessCode",
                      "authorizationUrl": "https://id.example.com/authorize",
                      "tokenUrl": "https://id.example.com/token",
                      "scopes": { "read": "Read things" }
                    }
                  },
                  "paths": {
                    "/x": { "get": { "operationId": "x", "responses": { "204": { "description": "ok" } } } }
                  }
                }
            """,
        )
        imported(document) shouldContain "oauth2AuthorizationCode("
        imported(document) shouldContain """scopes = mapOf("read" to "Read things")"""
    }

    @Test
    fun `3 0's nullable is 3 1's null among the types`() {
        val document = documentOf(
            "openapi.yaml" to """
                openapi: 3.0.3
                info:
                  title: T
                  version: "1"
                paths:
                  /x:
                    get:
                      operationId: x
                      responses:
                        "200":
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [note]
                                properties:
                                  note:
                                    type: string
                                    nullable: true
            """,
        )
        // Required, so nothing but the `nullable` could have put the `?` there.
        imported(document) shouldContain "val note: String?,"
    }
}
