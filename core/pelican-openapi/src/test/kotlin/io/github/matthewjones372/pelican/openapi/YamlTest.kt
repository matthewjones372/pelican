package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonNull
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.emptyJsonObj
import io.github.matthewjones372.pelican.jsonArr
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.jsonStrings
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class YamlTest {

    @Test
    fun `nests mappings under their key`() {
        jsonObj {
            "openapi" to "3.1.0"
            put("info", jsonObj { "title" to "Orders" })
        }.renderYaml() shouldBe
            """
            openapi: 3.1.0
            info:
              title: Orders
            """.trimIndent() + "\n"
    }

    @Test
    fun `writes a sequence of mappings with the dash over the first key`() {
        jsonObj {
            put(
                "servers",
                jsonArr(
                    listOf(
                        jsonObj {
                            "url" to "http://localhost:8080"
                            "description" to "local"
                        },
                        jsonObj { "url" to "https://example.test" },
                    ),
                ),
            )
        }.renderYaml() shouldBe
            """
            servers:
              - url: http://localhost:8080
                description: local
              - url: https://example.test
            """.trimIndent() + "\n"
    }

    @Test
    fun `writes a sequence of scalars inline`() {
        jsonObj { put("required", jsonStrings(listOf("id", "note"))) }.renderYaml() shouldBe
            """
            required:
              - id
              - note
            """.trimIndent() + "\n"
    }

    @Test
    fun `nests a sequence inside a sequence`() {
        jsonObj {
            put("matrix", jsonArr(listOf(jsonStrings(listOf("a", "b")), jsonStrings(listOf("c")))))
        }.renderYaml() shouldBe
            """
            matrix:
              - - a
                - b
              - - c
            """.trimIndent() + "\n"
    }

    /** An empty mapping has no block form, so it is written the JSON way. */
    @Test
    fun `writes empty collections in flow style`() {
        jsonObj {
            put("components", emptyJsonObj)
            put("tags", jsonArr(emptyList()))
            put("items", jsonArr(listOf(emptyJsonObj)))
        }.renderYaml() shouldBe
            """
            components: {}
            tags: []
            items:
              - {}
            """.trimIndent() + "\n"
    }

    @Test
    fun `quotes what YAML would read as another type`() {
        jsonObj {
            "version" to "1.0"       // a float
            "patch" to "1.0.0"       // not a float; three dots is a string
            "enabled" to "true"      // a boolean
            "nothing" to "null"      // a null
            "answer" to "42"         // an integer
            "mask" to "0x1f"         // a hex integer
            "released" to "2026-08-21" // a timestamp, to some readers
            "norway" to "no"         // the famous one
        }.renderYaml() shouldBe
            """
            version: "1.0"
            patch: 1.0.0
            enabled: "true"
            nothing: "null"
            answer: "42"
            mask: "0x1f"
            released: "2026-08-21"
            norway: "no"
            """.trimIndent() + "\n"
    }

    @Test
    fun `quotes what carries an indicator where it means something`() {
        jsonObj {
            "leading" to "- not a sequence"
            "colon" to "key: value"
            "hash" to "trailing # comment"
            "anchor" to "*star"
            "padded" to " spaced "
            "empty" to ""
            "url" to "https://example.test/orders"
            "safe" to "no#hash and a-dash"
        }.renderYaml() shouldBe
            """
            leading: "- not a sequence"
            colon: "key: value"
            hash: "trailing # comment"
            anchor: "*star"
            padded: " spaced "
            empty: ""
            url: https://example.test/orders
            safe: no#hash and a-dash
            """.trimIndent() + "\n"
    }

    @Test
    fun `writes a multi-line string as a literal block`() {
        jsonObj {
            "description" to "The first line.\n\nThe third."
            put("info", jsonObj { "summary" to "Indented\nunder its key." })
        }.renderYaml() shouldBe
            """
            description: |-
              The first line.

              The third.
            info:
              summary: |-
                Indented
                under its key.
            """.trimIndent() + "\n"
    }

    @Test
    fun `quotes a multi-line string a block would not round-trip`() {
        jsonObj {
            "trailing" to "one \ntwo"
            "closing" to "one\ntwo\n"
            "carriage" to "one\r\ntwo"
        }.renderYaml() shouldBe
            """
            trailing: "one \ntwo"
            closing: "one\ntwo\n"
            carriage: "one\r\ntwo"
            """.trimIndent() + "\n"
    }

    @Test
    fun `renders numbers, booleans and null unquoted`() {
        jsonObj {
            "count" to 3
            "ratio" to 1.5
            "enabled" to true
            put("nothing", JsonNull)
        }.renderYaml() shouldBe
            """
            count: 3
            ratio: 1.5
            enabled: true
            nothing: null
            """.trimIndent() + "\n"
    }

    @Test
    fun `quotes a key that needs it`() {
        JsonObj(mapOf("/orders/{id}" to JsonStr("path"), "on" to JsonStr("keyword"))).renderYaml() shouldBe
            """
            /orders/{id}: path
            "on": keyword
            """.trimIndent() + "\n"
    }

    @Test
    fun `renders a bare scalar or an empty document`() {
        JsonStr("alone").renderYaml() shouldBe "alone\n"
        emptyJsonObj.renderYaml() shouldBe "{}\n"
        jsonArr(emptyList()).renderYaml() shouldBe "[]\n"
    }
}
