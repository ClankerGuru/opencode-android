package ai.opencode.feature.session

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.Store
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodeTranscriptItem
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * iOS `ComposerIdentityTests`: pushing and popping a subagent flips the session screen's
 * `isCovered`, and the composer's text field must survive it (a recreated field drops the
 * keyboard and the draft's selection). iOS hosts `SessionView` in a `TestWindowHost` and compares
 * the `ImagePasteTextView`'s `ObjectIdentifier`; Android hosts it in a Robolectric activity and
 * compares the composer `BasicTextField`'s semantics node id, which lives as long as its layout node.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposerIdentityTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun pushingAndPoppingSubagentKeepsComposerStructuralIdentity() {
        val state = makeSessionState(
            id = "ses_identity",
            title = "Identity check",
            isRunning = true,
            queuedInputs = listOf(OpenCodePendingInput(id = "msg_q", text = "Queued", delivery = OpenCodeDelivery.Queue)),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // iOS flips `isCovered` on `.refreshRequestsTapped` (no Android action); here the test
        // sends the toggled state as `PreviewStateReplaced`, and every other action is ignored.
        val sessionStore = Store(state, Reducer<SessionFeature.State, SessionFeature.Action> { current, action ->
            if (action is SessionFeature.Action.PreviewStateReplaced) {
                Reduced(action.state)
            } else {
                Reduced(current)
            }
        }, scope = scope)
        fun toggleCovered() {
            sessionStore.send(SessionFeature.Action.PreviewStateReplaced(sessionStore.state.copy(isCovered = !sessionStore.state.isCovered)))
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        try {
            view.setContent { OpenCodeAppTheme { SessionView(store = sessionStore) } }
            pump()
            val initialTextID = findTextField(view)?.id
            assertNotNull("The composer's text field is on screen", initialTextID)

            // Push subagent (`isCovered` flips false -> true).
            toggleCovered()
            pump()
            assertEquals(true, sessionStore.state.isCovered)
            assertEquals("Pushing a subagent must not recreate the composer's text field", initialTextID, findTextField(view)?.id)

            // Pop subagent (`isCovered` flips true -> false).
            toggleCovered()
            pump()
            assertEquals(false, sessionStore.state.isCovered)
            assertEquals("Popping a subagent must not recreate the composer's text field", initialTextID, findTextField(view)?.id)
        } finally {
            activity.finish()
            scope.cancel()
        }
    }

    /** iOS `TestWindowHost.pump()`: lets a few frames run. */
    private fun pump() {
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
        }
    }

    /** iOS `findTextView(in:)`: the composer's editable field, found through the semantics tree. */
    private fun findTextField(view: ComposeView): SemanticsNode? {
        val root = findRoot(view) ?: return null
        return findEditable(root.semanticsOwner.unmergedRootSemanticsNode)
    }

    private fun findRoot(view: View): RootForTest? {
        if (view is RootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = findRoot(view.getChildAt(index))
                if (found != null) return found
            }
        }
        return null
    }

    private fun findEditable(node: SemanticsNode): SemanticsNode? {
        if (node.config.getOrNull(SemanticsProperties.EditableText) != null) return node
        for (child in node.children) {
            val found = findEditable(child)
            if (found != null) return found
        }
        return null
    }

    private fun makeSessionState(
        id: String,
        title: String,
        isRunning: Boolean = false,
        queuedInputs: List<OpenCodePendingInput> = emptyList(),
    ): SessionFeature.State {
        val state = SessionFeature.State.session(
            server = testServer,
            session = testSession(id = id, title = title),
            cachedItems = listOf(OpenCodeTranscriptItem.UserText(id = "u1", text = "Hello")),
            isRunning = isRunning,
        )
        return state.copy(readsOnView = false, queuedInputs = queuedInputs)
    }
}
