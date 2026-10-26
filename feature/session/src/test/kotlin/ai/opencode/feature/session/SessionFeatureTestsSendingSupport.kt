package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.UndeliveredPrompts
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.ServerIDClient
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.hours

/**
 * Helpers the iOS `SessionFeatureTests` extensions ported in this group share (`sentID` and
 * `failedSendStore` live in iOS `SessionFeatureTests+FailedSends.swift`; copies here so these
 * files don't depend on that port).
 */
internal object SessionFeatureTestsSendingSupport {
    /** iOS `SessionFeatureTests.sentID`: the first message ID `ServerIDClient.incrementing()` mints. */
    const val sentID = "msg_00000000000100000000000000"

    // `#expect` in kotlin.test's argument order (expected, actual, message), over JUnit's asserts.
    fun <T> assertEquals(expected: T, actual: T, message: String? = null) = org.junit.Assert.assertEquals(message, expected, actual)
    fun assertTrue(condition: Boolean, message: String? = null) = org.junit.Assert.assertTrue(message, condition)
    fun assertFalse(condition: Boolean, message: String? = null) = org.junit.Assert.assertFalse(message, condition)
    fun assertNull(value: Any?, message: String? = null) = org.junit.Assert.assertNull(message, value)
    fun assertNotNull(value: Any?, message: String? = null) = org.junit.Assert.assertNotNull(message, value)

    /** iOS `Task.sleep(for: .seconds(3600))` in a stub: a wait the test never sees end. */
    suspend fun hang() = delay(1.hours)

    /** iOS `AsyncStream<Void>.makeStream()` used as a gate: `for await _ in gate { break }`. */
    class Gate {
        private val channel = Channel<Unit>(Channel.UNLIMITED)
        suspend fun await() {
            channel.receive()
        }
        fun open() {
            channel.trySend(Unit)
        }
    }

    /**
     * iOS `failedSendStore`: a send store whose POSTs follow [attempt] (by its 1-based number) and
     * whose message reads find the prompt only once [serverHasIt] is set.
     */
    fun SessionTestSupport.Harness.failedSendStore(
        state: SessionFeature.State,
        kept: UndeliveredPrompts = UndeliveredPrompts(),
        attempts: LockIsolated<List<Pair<String, OpenCodeDelivery>>>,
        serverHasIt: LockIsolated<Boolean> = LockIsolated(false),
        attempt: (Int) -> Unit,
    ): TestStore<SessionFeature.State, SessionFeature.Action> = store(state) {
        this[UuidClient] = UuidClient.incrementing()
        this[ServerIDClient] = ServerIDClient.incrementing()
        this[Clock] = ImmediateClock()
        this[UndeliveredPrompts] = kept
        this[OpenCodeClient] = this[OpenCodeClient].copy(
            sendPrompt = { _, _, _, _, _, messageID, delivery ->
                val count = attempts.withValue { (it + (messageID to delivery)).let { next -> next to next.size } }
                attempt(count)
            },
            message = { _, _, messageID ->
                if (!serverHasIt.value) throw OpenCodeClientError(message = "Missing", statusCode = 404)
                listOf(OpenCodeTranscriptItem.UserText(id = messageID, text = "hello"))
            },
            pendingInputs = { _, _ -> emptyList() },
            getSession = { _, _ -> hang(); testSession() },
            transcript = { _, _, _ -> hang(); emptyList() },
            runningShells = { _, _ -> emptyList() },
            familyPendingRequests = { _, _, _ -> throw OpenCodeClientError(message = "Offline") },
            // The reply's wait isn't part of these tests.
            waitForIdle = { _, _ -> hang() },
        )
    }
}
