package ai.opencode.feature.voice

import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * One Shape: which stage the floating call settles on and its haptics (iOS
 * `FloatingCallStageTests`, the store half). The geometry, window and connect-motion suites
 * wait on the One Shape views' port (see voice.md).
 */
class FloatingCallStageTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun TestScope.store(
        state: FloatingCallStageFeature.State = FloatingCallStageFeature.State(),
        haptics: VoiceFeatureTestSupport.HapticsRecorder,
    ) = TestStore(state, FloatingCallStageFeature(), this) { haptics.install(this) }

    @Test fun tappingTheOrbGrowsItIntoTheCard() = runTest {
        val log = VoiceFeatureTestSupport.HapticsRecorder()
        val store = store(haptics = log)
        store.send(FloatingCallStageFeature.Action.OrbTapped) { it.copy(stage = FloatingCallStage.card) }
        // Only the orb takes the tap.
        store.send(FloatingCallStageFeature.Action.OrbTapped)
        assertEquals(listOf("tap"), log.log)
    }

    @Test fun aDragTicksAtEachStageItCrossesAndSnapsWhereItLands() = runTest {
        val log = VoiceFeatureTestSupport.HapticsRecorder()
        val store = store(FloatingCallStageFeature.State(stage = FloatingCallStage.card), log)
        store.send(FloatingCallStageFeature.Action.DragBegan) { it.copy(dragTarget = FloatingCallStage.card) }
        // Still within the card's reach: nothing to feel.
        store.send(FloatingCallStageFeature.Action.DragCrossed(FloatingCallStage.card))
        store.send(FloatingCallStageFeature.Action.DragCrossed(FloatingCallStage.full)) { it.copy(dragTarget = FloatingCallStage.full) }
        store.send(FloatingCallStageFeature.Action.DragCrossed(FloatingCallStage.full))
        store.send(FloatingCallStageFeature.Action.DragEnded(FloatingCallStage.full)) {
            it.copy(dragTarget = null, stage = FloatingCallStage.full)
        }
        assertEquals(listOf("threshold", "snap"), log.log)
    }

    @Test fun aDragLetGoWhereItStartedSettlesQuietly() = runTest {
        val log = VoiceFeatureTestSupport.HapticsRecorder()
        val store = store(FloatingCallStageFeature.State(stage = FloatingCallStage.card), log)
        store.send(FloatingCallStageFeature.Action.DragBegan) { it.copy(dragTarget = FloatingCallStage.card) }
        store.send(FloatingCallStageFeature.Action.DragCrossed(FloatingCallStage.orb)) { it.copy(dragTarget = FloatingCallStage.orb) }
        store.send(FloatingCallStageFeature.Action.DragCrossed(FloatingCallStage.card)) { it.copy(dragTarget = FloatingCallStage.card) }
        store.send(FloatingCallStageFeature.Action.DragEnded(FloatingCallStage.card)) { it.copy(dragTarget = null) }
        assertEquals(listOf("threshold", "threshold"), log.log)
    }

    @Test fun aFlickDownFromTheSheetStepsBackToTheCard() = runTest {
        val log = VoiceFeatureTestSupport.HapticsRecorder()
        val store = store(FloatingCallStageFeature.State(stage = FloatingCallStage.full), log)
        store.send(FloatingCallStageFeature.Action.DragBegan) { it.copy(dragTarget = FloatingCallStage.full) }
        store.send(FloatingCallStageFeature.Action.DragEnded(FloatingCallStage.card)) {
            it.copy(dragTarget = null, stage = FloatingCallStage.card)
        }
        assertEquals(listOf("snap"), log.log)
    }

    @Test fun aTapMidDragIsIgnored() = runTest {
        val log = VoiceFeatureTestSupport.HapticsRecorder()
        val store = store(haptics = log)
        store.send(FloatingCallStageFeature.Action.DragBegan) { it.copy(dragTarget = FloatingCallStage.orb) }
        store.send(FloatingCallStageFeature.Action.OrbTapped)
        assertTrue(log.log.isEmpty())
    }

    @Test fun theChevronAndEscapeStepBackQuietly() = runTest {
        val log = VoiceFeatureTestSupport.HapticsRecorder()
        val store = store(FloatingCallStageFeature.State(stage = FloatingCallStage.full), log)
        store.send(FloatingCallStageFeature.Action.StepBack) { it.copy(stage = FloatingCallStage.card) }
        store.send(FloatingCallStageFeature.Action.StepBack) { it.copy(stage = FloatingCallStage.orb) }
        store.send(FloatingCallStageFeature.Action.StepBack)
        store.send(FloatingCallStageFeature.Action.Requested(FloatingCallStage.full)) { it.copy(stage = FloatingCallStage.full) }
        store.send(FloatingCallStageFeature.Action.MinimizeTapped) { it.copy(stage = FloatingCallStage.orb) }
        assertTrue(log.log.isEmpty())
    }

    /**
     * A pending request docks in the composer, which the sheet covers: the call steps down to
     * the card above it. The orb and the card already leave it showing, and a drag in progress
     * is the user's.
     */
    @Test fun aRequestDockingStepsTheSheetDownToTheCard() = runTest {
        val log = VoiceFeatureTestSupport.HapticsRecorder()
        val store = store(FloatingCallStageFeature.State(stage = FloatingCallStage.full), log)
        store.send(FloatingCallStageFeature.Action.RequestDocked) { it.copy(stage = FloatingCallStage.card) }
        store.send(FloatingCallStageFeature.Action.RequestDocked)
        store.send(FloatingCallStageFeature.Action.MinimizeTapped) { it.copy(stage = FloatingCallStage.orb) }
        store.send(FloatingCallStageFeature.Action.RequestDocked)
        store.send(FloatingCallStageFeature.Action.Requested(FloatingCallStage.full)) { it.copy(stage = FloatingCallStage.full) }
        store.send(FloatingCallStageFeature.Action.DragBegan) { it.copy(dragTarget = FloatingCallStage.full) }
        store.send(FloatingCallStageFeature.Action.RequestDocked)
        assertTrue(log.log.isEmpty())
    }

    @Test fun theCallKeepsItsStage() = runTest {
        val log = VoiceFeatureTestSupport.HapticsRecorder()
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", serverTitle = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = VoiceFeatureTestSupport.distantPast)
        val store = TestStore(VoiceFeature.State(server, session), VoiceFeature(), this) { log.install(this) }
        store.send(VoiceFeature.Action.FloatingStage(FloatingCallStageFeature.Action.OrbTapped)) {
            it.copy(floatingStage = it.floatingStage.copy(stage = FloatingCallStage.card))
        }
        store.send(VoiceFeature.Action.FloatingStage(FloatingCallStageFeature.Action.DragBegan)) {
            it.copy(floatingStage = it.floatingStage.copy(dragTarget = FloatingCallStage.card))
        }
        store.send(VoiceFeature.Action.FloatingStage(FloatingCallStageFeature.Action.DragEnded(FloatingCallStage.full))) {
            it.copy(floatingStage = it.floatingStage.copy(dragTarget = null, stage = FloatingCallStage.full))
        }
        assertEquals(listOf("tap", "snap"), log.log)
    }
}
