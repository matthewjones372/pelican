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
 * So the fixtures below are compiled for real and the messages asserted. Where
 * a page quotes a whole compiler line, the whole line is pinned and the fixture
 * is declared with the names that page uses, because the quotation is the
 * claim: two of them said `Function1<String, User>` for a handler the compiler
 * has always called `Params.(String) -> User`. Where it quotes only a type, the
 * type is pinned and the wording stays Kotlin's to change.
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
    """.trimIndent()

    /**
     * The declarations behind the reference's typechecked-endpoint examples,
     * named as that page names them so the lines it quotes are the lines the
     * compiler emits here.
     */
    private val typed = """
        import io.github.matthewjones372.pelican.*
        import io.github.matthewjones372.pelican.pekko.*
        import org.apache.pekko.stream.javadsl.Source

        data class User(val id: Long)
        data class Order(val id: Long)
        data class Tick(val n: Long)

        val userId = pathParam<Long>("userId")
        val limit = queryParam<Int>("limit").default(25)

        val getUser = endpoint(userId) { get("users" / userId); json<User>() }
        val streamOrders = endpoint(userId, limit) { get("users" / userId / "orders"); ndjson<Order>() }
        val watchOrders = endpoint(userId, limit) { get("users" / userId / "ticks"); sse<Tick>() }
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
        val errors = compile("$typed\nval bound = getUser handledNow { id -> \"a string\" }")

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain "Return type mismatch: expected 'User', actual 'String'."
        }
    }

    /**
     * The mistake a caller makes on first contact: a declaration is a value, so
     * `err(theDeclaration)` type-checks as a payload of its own and fails as a
     * type nobody wrote. The guard turns it into a sentence naming the fix.
     */
    @Test
    fun `passing a declaration to err is refused where it is written`() {
        val errors = compile("$preamble\nval bound = fallible handledOrFail { err(gone) }")

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain "A declared failure is not a payload"
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

    @Test
    fun `a handler taking an input the endpoint types differently names both handler types`() {
        val errors = compile("$typed\nval bound = getUser handledNow { id: String -> User(1) }")

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain
                "Argument type mismatch: actual type is 'Params.(String) -> User', " +
                "but 'Params.(Long) -> User' was expected."
        }
    }

    @Test
    fun `a stream of the wrong element names the element the output declared`() {
        val errors = compile(
            "$typed\nval bound = watchOrders streamedNow { (_, max) -> Source.single(\"not a tick\") }",
        )

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain
                "Return type mismatch: expected 'Source<Tick, NotUsed>', actual 'Source<String!, NotUsed!>!'."
        }
    }

    @Test
    fun `a value where the endpoint declared a stream names StreamOf`() {
        val errors = compile("$typed\nval bound = streamOrders handledNow { _ -> User(1) }")

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain
                "Return type mismatch: expected 'StreamOf<Order>', actual 'User'."
        }
    }

    /** The reference's declared-failure examples, under the names it gives them. */
    private val failing = """
        import io.github.matthewjones372.pelican.*
        import io.github.matthewjones372.pelican.pekko.*

        data class User(val id: Long)
        data class ApiError(val status: Int, val message: String)
        data class OtherProblem(val why: String)

        val userId = pathParam<Long>("userId")
        val noSuchUser = errorJson<ApiError>(404, "No user with that id")
        val otherFailure = errorJson<OtherProblem>(409, "Declared by another endpoint")

        val getUser = endpoint(userId) { get("users" / userId); json<User>() orFail noSuchUser }
    """.trimIndent()

    @Test
    fun `a bare value where the endpoint declares a failure names the Outcome it promised`() {
        val errors = compile("$failing\nval bound = getUser handledOrFail { id -> User(id) }")

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain
                "Return type mismatch: expected 'Outcome<ApiError, User>', actual 'User'."
        }
    }

    @Test
    fun `a failure carrying a type outside the declared one names what it carries`() {
        val errors = compile(
            "$failing\nval bound = getUser handledOrFail { _ -> otherFailure(OtherProblem(\"no\")) }",
        )

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain
                "Return type mismatch: expected 'Outcome<ApiError, User>', " +
                "actual 'Outcome<OtherProblem, Nothing>'."
        }
    }

    @Test
    fun `the bookmarks example the README quotes produces the line the README quotes`() {
        val errors = compile(
            """
            import io.github.matthewjones372.pelican.*
            import io.github.matthewjones372.pelican.pekko.*

            data class Bookmark(val id: Long)
            data class NoSuchBookmark(val id: Long, val message: String)

            val bookmarkId = pathParam<Long>("bookmarkId")
            val bookmarkMissing = errorJson<NoSuchBookmark>(404, "No bookmark with that id")
            val getBookmark = endpoint(bookmarkId) {
                get("bookmarks" / bookmarkId)
                json<Bookmark>() orFail bookmarkMissing
            }
            val bound = getBookmark handledOrFail { id -> Bookmark(id) }
            """.trimIndent(),
        )

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain
                "Return type mismatch: expected 'Outcome<NoSuchBookmark, Bookmark>', actual 'Bookmark'."
        }
    }

    @Test
    fun `a seventh input has no overload to land on`() {
        val errors = compile(
            """
            $preamble
            val a = queryParam<Int>("a")
            val b = queryParam<Int>("b")
            val c = queryParam<Int>("c")
            val d = queryParam<Int>("d")
            val e = queryParam<Int>("e")
            val f = queryParam<Int>("f")
            val g = queryParam<Int>("g")
            val seven = endpoint(a, b, c, d, e, f, g) { get("z"); json<Item>() }
            """.trimIndent(),
        )

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain "None of the following candidates is applicable"
            errors.joinToString("\n") shouldContain "Too many arguments"
        }
    }

    /**
     * The other hole the reference admits, pinned open beside the first.
     *
     * `endpoint(lensInputs)` hands the handler the whole bag, so a key nothing
     * declared is a read the compiler cannot object to; `Params.get` throws on
     * the request instead, which is why the lens form is a name rather than the
     * default.
     */
    @Test
    fun `a lens handler reading a key the endpoint never declared compiles`() {
        val errors = compiles(
            """
            $preamble
            val limit = queryParam<Int>("limit").default(25)
            val stray = queryParam<String>("stray")
            val searching = endpoint(lensInputs) { get("search"); query(limit); json<Item>() }
            val bound = searching handledNow { p -> Item(p[stray].length.toLong()) }
            """.trimIndent(),
        )

        withClue("if this ever stops compiling, the lens form's documented trade is gone") {
            errors.shouldBeEmpty()
        }
    }
}
