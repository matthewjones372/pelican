package example.compiler

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The mistakes the README says do not compile, compiled.
 *
 * Those pages quote the compiler's own words — "Return type mismatch: expected
 * …" — which is the most persuasive thing they can do and the easiest thing to
 * get wrong: the type in that message is Pelican's, so renaming it makes every
 * quotation false at once and nothing notices. Four of them were stale in this
 * repository until somebody read them.
 *
 * So the fixtures below are compiled for real and the messages asserted. What
 * is pinned is not the wording — that is Kotlin's to change — but the *type* the
 * message names, which is the part the library controls and the part a reader
 * is being asked to believe.
 */
class DoesNotCompileTest {

    @TempDir
    lateinit var workspace: File

    /** As [compile], for a fixture that is expected to be accepted. */
    private fun compiles(source: String): List<String> = build(source).second

    private fun compile(source: String): List<String> {
        val (exit, errors) = build(source)
        withClue("the fixture compiled, and this test exists because it must not") {
            exit shouldNotBe ExitCode.OK
        }
        return errors
    }

    private fun build(source: String): Pair<ExitCode, List<String>> {
        val file = File(workspace, "Fixture.kt").apply { writeText(source) }
        val errors = mutableListOf<String>()
        val collector = object : MessageCollector {
            override fun clear() = errors.clear()
            override fun hasErrors() = errors.isNotEmpty()
            override fun report(
                severity: CompilerMessageSeverity,
                message: String,
                location: CompilerMessageSourceLocation?,
            ) {
                if (severity.isError) errors += message
            }
        }

        val exit = K2JVMCompiler().exec(
            collector,
            Services.EMPTY,
            K2JVMCompilerArguments().apply {
                freeArgs = listOf(file.absolutePath)
                classpath = System.getProperty("java.class.path")
                destination = File(workspace, "out").absolutePath
                noStdlib = true
                noReflect = true
                // The modules on the classpath are built for 21; without this
                // the compiler defaults to 1.8 and every fixture fails for a
                // reason that has nothing to do with what is being asserted.
                jvmTarget = "21"
            },
        )

        return exit to errors
    }

    private val preamble = """
        import io.github.matthewjones372.pelican.*
        import io.github.matthewjones372.pelican.pekko.*

        data class Item(val id: Long)
        data class Problem(val why: String)

        val gone = errorJson<Problem>(410, "Gone")
        val fallible = endpoint { get("x"); json<Item>() orFail gone }
        val total = endpoint { get("y"); json<Item>() }
    """.trimIndent()

    @Test
    fun `a total binder on an endpoint that declares a failure names Outcome`() {
        val errors = compile("$preamble\nval bound = fallible handledNow { Item(1) }")

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain "Outcome<Problem, Item>"
        }
    }

    @Test
    fun `a handler returning the wrong payload names the type the endpoint declared`() {
        val errors = compile("$preamble\nval bound = total handledNow { \"not an item\" }")

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain "Item"
        }
    }

    /**
     * The hole, written down as a passing test rather than a paragraph.
     *
     * `E` is pinned to the failure's *payload type*, not to the `ErrorOutput`
     * that declared it — so any failure carrying the same type fits, including
     * one belonging to another endpoint. It does not need a sealed hierarchy to
     * widen `E` first, which is what the reference manual used to imply: one
     * declared failure is enough, and `ApiError` is the payload of most of them.
     *
     * What catches it is `UndeclaredResponse` when the response is written.
     */
    @Test
    fun `a failure of the same payload type compiles, whichever endpoint declared it`() {
        val errors = compiles(
            "$preamble\nval other = errorJson<Problem>(409, \"Elsewhere\")\n" +
                "val bound = fallible handledOrFail { other(Problem(\"no\")) }",
        )

        withClue("if this ever stops compiling, the reference manual is wrong the other way") {
            errors.shouldBeEmpty()
        }
    }
}
