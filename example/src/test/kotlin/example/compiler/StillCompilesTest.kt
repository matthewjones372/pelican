package example.compiler

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * The names the `.api` dumps cannot see, pinned by compiling them.
 *
 * Binary-compatibility validation reads the bytecode a module publishes, and
 * the most-typed half of this DSL never reaches it: `jsonValue<T>()`, `jsonBody<T>()`,
 * `pathParam<T>()`, `sse<T>()`, `errorJson<T>()` and the rest are reified
 * inline functions, so what a caller compiles against is the *source*, and the
 * only artefact left in the jar is an ACC_SYNTHETIC copy no dump lists.
 * Renaming `json` to `jsonValue` would therefore break every user of the
 * library and pass `apiCheck`, detekt, Kover and every test in this repository.
 *
 * So the call sites are compiled instead. `frozenCallSites` is the source a
 * user types, one fixture per area, and each is handed to the Kotlin compiler
 * against this module's own classpath — which is the published modules. A
 * fixture that stops compiling fails the case named after its area and prints
 * the compiler's own words.
 *
 * This is the positive twin of [DoesNotCompileTest], and the two are the same
 * argument in both directions: that one pins the errors the manual quotes,
 * this one pins the calls the manual teaches.
 */
class StillCompilesTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("areas")
    fun `the frozen call sites still compile`(area: String) {
        withClue(report(area)) { errorsIn(area).shouldBeEmpty() }
    }

    /**
     * A fixture whose source the compiler could not attribute to a file — a
     * missing dependency, a classpath that no longer resolves — would leave
     * every case above green while nothing was really checked.
     */
    @Test
    fun `nothing failed outside a fixture`() {
        withClue(unattributed().joinToString("\n")) { unattributed().shouldBeEmpty() }
    }

    private fun errorsIn(area: String): List<String> = failures.getValue(area)

    private fun unattributed(): List<String> = failures.getValue(UNATTRIBUTED)

    private fun report(area: String): String =
        "the pinned $area call sites no longer compile, so a rename has already broken " +
            "every caller written against them:\n\n" + errorsIn(area).joinToString("\n\n")

    companion object {

        @JvmStatic
        fun areas(): List<String> = frozenCallSites.keys.toList()

        @JvmField
        @TempDir
        var workspace: File = File(".")

        /** Where an error with no source location of its own is filed. */
        private const val UNATTRIBUTED = "(no fixture)"

        /**
         * One compiler run for every fixture rather than one each: starting
         * K2JVMCompiler costs more than compiling these files does, and the
         * location on each message is what puts a failure back with the area
         * it came from.
         */
        private val failures: Map<String, List<String>> by lazy { compileAll() }

        private fun compileAll(): Map<String, List<String>> {
            val files = frozenCallSites.map { (area, source) -> area to write(area, source) }
            val reported = mutableListOf<Pair<String, String>>()

            val collector = object : MessageCollector {
                override fun clear() = reported.clear()
                override fun hasErrors() = reported.isNotEmpty()
                override fun report(
                    severity: CompilerMessageSeverity,
                    message: String,
                    location: CompilerMessageSourceLocation?,
                ) {
                    if (!severity.isError) return
                    val where = location?.path?.let { File(it).nameWithoutExtension }
                    reported += (where ?: UNATTRIBUTED) to describe(message, location)
                }
            }

            K2JVMCompiler().exec(
                collector,
                Services.EMPTY,
                K2JVMCompilerArguments().apply {
                    freeArgs = files.map { it.second.absolutePath }
                    classpath = System.getProperty("java.class.path")
                    destination = File(workspace, "out").absolutePath
                    noStdlib = true
                    noReflect = true
                    // The modules on the classpath are built for 21; without
                    // this the compiler defaults to 1.8 and every fixture fails
                    // for a reason that has nothing to do with what is asserted.
                    jvmTarget = "21"
                },
            )

            val byArea = reported.groupBy({ it.first }, { it.second })
            return (frozenCallSites.keys + UNATTRIBUTED).associateWith { byArea[it].orEmpty() }
        }

        private fun describe(message: String, location: CompilerMessageSourceLocation?): String =
            if (location == null) message
            else "line ${location.line}: $message\n    ${location.lineContent?.trim()}"

        /**
         * A package per fixture, so two areas may both describe an `Order`, and
         * so the file name the compiler reports is the area's own name.
         */
        private fun write(area: String, source: String): File =
            File(workspace, "$area.kt").apply {
                writeText("package frozen.$area\n\n" + source.trimIndent())
            }
    }
}
