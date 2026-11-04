package ai.opencode.server

import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.testing.TestIsolationRule
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/** The `EventBatcher` test of iOS `MultipleWindowsTests.swift` (the rest belongs to `SharedEventStreams`' suite). */
class EventBatcherTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun batcherOverflowFinishesAndCancelsFurtherEmission() = runTest {
        for (path in listOf("boundary", "sessionFlush", "allFlush")) {
            // A one-event buffer whose overflow ends the stream, as the live subscription's does.
            val channel = Channel<OpenCodeServerEvent>(1)
            val batcher = EventBatcher(
                scope = backgroundScope,
                emit = { event ->
                    val result = channel.trySend(event)
                    when {
                        result.isSuccess -> EventEmission.Enqueued(remaining = 0)
                        result.isClosed -> EventEmission.Terminated
                        else -> {
                            channel.close(EventStreamOverflow())
                            EventEmission.Dropped
                        }
                    }
                },
                clock = TestClock(),
            )
            batcher.receive(OpenCodeServerEvent.Connected)
            if (path == "boundary") {
                batcher.receive(OpenCodeServerEvent.ExecutionEnded("s"))
            } else {
                batcher.receive(OpenCodeServerEvent.ContentBatch("s", listOf(OpenCodeLiveContentEvent.TextDelta("m", 0, "part"))))
                if (path == "sessionFlush") {
                    batcher.receive(OpenCodeServerEvent.ExecutionEnded("s"))
                } else {
                    batcher.flush()
                }
            }
            batcher.receive(OpenCodeServerEvent.ExecutionStarted("must-not-arrive"))
            try {
                for (event in channel) {
                    assertNotEquals(path, OpenCodeServerEvent.ExecutionStarted("must-not-arrive"), event)
                }
                fail("$path: the direct stream must report overflow")
            } catch (_: EventStreamOverflow) {
                // Expected.
            }
        }
    }
}
