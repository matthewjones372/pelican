package io.github.matthewjones372.pelican.gannet

import io.github.matthewjones372.gannet.GannetSupport
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Whether Gannet's tests run on this host: [GannetSupport]'s answer, unless `PELICAN_REQUIRE_URING=1` says this host
 * must run them, in which case they run and fail where io_uring is unusable.
 */
object GannetTests {
    private val required: Boolean = System.getenv("PELICAN_REQUIRE_URING") == "1"

    /** Null when the Gannet tests should run here, else why they skip. */
    val skipReason: String? = GannetSupport.problem()?.takeUnless { required }

    /** True where the Gannet tests run, so a suite over several backends keeps Gannet in its list. */
    val run: Boolean get() = skipReason == null
}

/** Skips a test where Gannet cannot run, unless `PELICAN_REQUIRE_URING=1` says this host must run it. */
fun assumeGannet() {
    val problem = GannetSupport.problem() ?: return
    Assumptions.assumeTrue(GannetTests.run, problem)
    throw AssertionError("PELICAN_REQUIRE_URING=1, but: $problem")
}

/**
 * Skips a whole class or test where Gannet cannot run, as [assumeGannet] does. It is decided before the class
 * initialises, so a class whose companion starts a server is skipped rather than failing to load.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(GannetCondition::class)
annotation class RequiresGannet

/** What [RequiresGannet] runs. */
class GannetCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult =
        GannetTests.skipReason?.let { ConditionEvaluationResult.disabled(it) }
            ?: ConditionEvaluationResult.enabled("Gannet's tests run here")
}
