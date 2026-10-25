package io.github.matthewjones372.pelican.client.pekko

import org.apache.pekko.stream.Attributes
import org.apache.pekko.stream.FlowShape
import org.apache.pekko.stream.Inlet
import org.apache.pekko.stream.Outlet
import org.apache.pekko.stream.stage.AbstractInHandler
import org.apache.pekko.stream.stage.AbstractOutHandler
import org.apache.pekko.stream.stage.GraphStage
import org.apache.pekko.stream.stage.GraphStageLogic
import org.apache.pekko.stream.stage.TimerGraphStageLogic
import org.apache.pekko.util.ByteString
import java.time.Duration

/**
 * Turns a reader's cancellation of a response entity into reading the rest of it and throwing that away.
 *
 * Pekko's pool counts a cancelled entity as a finished one: the slot goes idle and takes the next request while
 * the connection under it is still closing, and a `POST` handed that slot fails with `StageWasCompleted` rather
 * than being retried (spec 0052). An entity read to its end leaves a connection that can be reused, so that is
 * what a close does here, up to [maxBytes] or [maxTime]. Past either, nothing more is read and the connection's
 * idle timeout fails it, which the pool counts as a failure and never reuses.
 */
internal class DrainOnCancel(
    private val maxBytes: Long,
    private val maxTime: Duration,
) : GraphStage<FlowShape<ByteString, ByteString>>() {

    private val bytesIn: Inlet<ByteString> = Inlet.create("DrainOnCancel.in")
    private val bytesOut: Outlet<ByteString> = Outlet.create("DrainOnCancel.out")
    private val stageShape: FlowShape<ByteString, ByteString> = FlowShape.of(bytesIn, bytesOut)

    override fun shape(): FlowShape<ByteString, ByteString> = stageShape

    override fun createLogic(inheritedAttributes: Attributes): GraphStageLogic = Logic()

    private inner class Logic : TimerGraphStageLogic(stageShape) {
        private var draining = false
        private var drained = 0L
        private var givenUp = false

        init {
            setHandler(
                bytesIn,
                object : AbstractInHandler() {
                    override fun onPush() {
                        val bytes = grab(bytesIn)
                        if (!draining) {
                            push(bytesOut, bytes)
                            return
                        }
                        drained += bytes.size()
                        if (drained > maxBytes) givenUp = true
                        if (!givenUp) pull(bytesIn)
                    }
                },
            )
            setHandler(
                bytesOut,
                object : AbstractOutHandler() {
                    override fun onPull() = pull(bytesIn)

                    override fun onDownstreamFinish(cause: Throwable) {
                        draining = true
                        setKeepGoing(true)
                        scheduleOnce(GIVE_UP, maxTime)
                        if (!isClosed(bytesIn) && !hasBeenPulled(bytesIn)) pull(bytesIn)
                    }
                },
            )
        }

        override fun onTimer(timerKey: Any) {
            givenUp = true
        }
    }

    private companion object {
        const val GIVE_UP = "give-up"
    }
}
