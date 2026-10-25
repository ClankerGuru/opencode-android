package ai.opencode.devtools

import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.tuning.InternalBuild
import ai.opencode.devtools.perf.LayoutBoundsOverlay
import ai.opencode.devtools.perf.PerfHud
import ai.opencode.devtools.tuning.TuningFeature
import ai.opencode.devtools.tuning.TuningPanel
import ai.opencode.core.store.collectState
import ai.opencode.core.ui.OpenCodeAppTheme
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Wraps the app's root content with the developer tools (iOS `TuningOverlay`'s window and
 * `TuningGestures`): the summon gesture, the floating Tuning Panel, the perf HUD and the layout
 * bounds overlay. Outside debug builds with Developer Mode off it is just [content].
 *
 * The summon gesture is a three-finger double tap or four fingers held for 0.45 s, anywhere; it
 * observes touches in the Initial pass without consuming them, so ordinary use can't tell it's
 * there.
 */
@Composable
fun DevToolsOverlay(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val developerModeShared = remember { shared(SharedKeys.developerMode) }
    val developerMode by developerModeShared.flow.collectAsState()
    val available = InternalBuild.isDebug || developerMode
    if (!available) {
        Box(modifier) { content() }
        return
    }
    val perfHud by remember { shared(DevToolsKeys.perfHud) }.flow.collectAsState()
    val layoutBounds by remember { shared(DevToolsKeys.layoutBounds) }.flow.collectAsState()
    val tuning by DevTools.tuningStore.collectState()
    Box(modifier.summonGesture { DevTools.summon() }) {
        content()
        OpenCodeAppTheme {
            if (layoutBounds) LayoutBoundsOverlay(Modifier.fillMaxSize())
            if (perfHud) PerfHud()
            TuningPanel(DevTools.tuningStore)
            if (tuning.showsDeveloperTools) {
                val close = { DevTools.tuningStore.send(TuningFeature.Action.DeveloperToolsClosed) }
                Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false)) {
                    val view = LocalView.current
                    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
                    SideEffect {
                        (view.parent as? DialogWindowProvider)?.window?.let { window ->
                            WindowCompat.getInsetsController(window, view).run {
                                isAppearanceLightStatusBars = light
                                isAppearanceLightNavigationBars = light
                            }
                        }
                    }
                    DevToolsView(DevTools.developerStore, Modifier.fillMaxSize(), onClose = close)
                }
            }
        }
    }
}

/** iOS `TuningGestures`: three-finger double tap, or four fingers held for 0.45 s. Never consumes. */
fun Modifier.summonGesture(onSummon: () -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        var lastTripleTapUp = 0L
        awaitPointerEventScope {
            while (true) {
                summonOnce(lastTripleTapUp, onSummon)?.let { lastTripleTapUp = it }
            }
        }
    },
)

private const val TAP_MAX_MS = 350L
private const val DOUBLE_TAP_WINDOW_MS = 500L
private const val HOLD_MS = 450L

/** Follows one gesture (first finger down to last up). Returns when a three-finger tap ended, its time. */
private suspend fun AwaitPointerEventScope.summonOnce(lastTripleTapUp: Long, onSummon: () -> Unit): Long? {
    var event = awaitPointerEvent(PointerEventPass.Initial)
    if (event.changes.none { it.pressed }) return null
    val start = event.changes.first().uptimeMillis
    var maxPressed = 0
    var fourSince = 0L
    var held = false
    while (true) {
        val pressed = event.changes.count { it.pressed }
        maxPressed = maxOf(maxPressed, pressed)
        val now = event.changes.maxOf { it.uptimeMillis }
        if (pressed == 4) {
            if (fourSince == 0L) fourSince = now
            if (!held && now - fourSince >= HOLD_MS) {
                held = true
                onSummon()
            }
        } else {
            fourSince = 0L
        }
        if (pressed == 0) {
            if (!held && maxPressed == 3 && now - start <= TAP_MAX_MS) {
                if (lastTripleTapUp != 0L && now - lastTripleTapUp <= DOUBLE_TAP_WINDOW_MS) {
                    onSummon()
                    return 0L
                }
                return now
            }
            return null
        }
        // Four fingers resting still send no events: wake when the hold is due.
        val timeout = if (fourSince != 0L && !held) (HOLD_MS - (now - fourSince)).coerceAtLeast(1L) else Long.MAX_VALUE
        val next = if (timeout == Long.MAX_VALUE) awaitPointerEvent(PointerEventPass.Initial) else withTimeoutOrNull(timeout) { awaitPointerEvent(PointerEventPass.Initial) }
        if (next == null) {
            if (!held) {
                held = true
                onSummon()
            }
            event = awaitPointerEvent(PointerEventPass.Initial)
        } else {
            event = next
        }
    }
}
