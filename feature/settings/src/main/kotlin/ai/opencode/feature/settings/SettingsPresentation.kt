package ai.opencode.feature.settings

import ai.opencode.core.diagnostics.HidesFromProblemReports
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Port of iOS `Settings/SettingsPresentation.swift`: Settings pushes full screen from the right
 * over the drawer and the session with its own stack; what's beneath parallaxes (30 % of the
 * width) and dims (black 12 %), and keeps its touches away while Settings is up. Back returns to
 * what was showing, the open drawer exactly as it was. Home stays composed underneath, never
 * replaced by a navigation push, so a session's work never restarts on the way back.
 *
 * The system back gesture at Settings' root follows the finger (Android predictive back in place
 * of iOS's `BackPanGestureRecognizer`) and closes on release; a cancelled swipe springs back open.
 * On a pushed page the stack's own back pops it first ([SettingsView]'s `NavDisplay`). Under
 * reduce motion it cross-fades in place (0.3 s ease-in-out). The push is iOS's
 * `.interpolatingSpring(duration: 0.42, bounce: 0)`.
 *
 * Home (`:feature:drawer`) wraps its content with this and passes Settings' content.
 *
 * @param isShown iOS `HomeFeature.State.isSettingsShown` (`settings != null`).
 * @param atRoot whether Settings shows its root page (`path.isEmpty()`): only then does the back
 *   gesture close Settings.
 * @param onClose the back gesture committed (send `SettingsFeature.Action.BackTapped`, which closes
 *   at the root via `SettingsDelegate.Close`).
 * @param settings Settings itself ([SettingsView]); kept composed while it slides out.
 * @param content what's beneath (Home).
 */
@Composable
fun SettingsPresentation(
    isShown: Boolean,
    atRoot: Boolean,
    onClose: () -> Unit,
    settings: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val progress = remember { Animatable(if (isShown) 1f else 0f) }
    var isMounted by remember { mutableStateOf(isShown) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    LaunchedEffect(isShown, reduceMotion) {
        if (isShown) {
            // Mounted off screen first, then pushed in, so the page slides rather than appearing in place.
            focus.clearFocus(force = true)
            isMounted = true
            progress.animateTo(1f, if (reduceMotion) FadeSpec else PushSpec)
        } else if (isMounted) {
            progress.animateTo(0f, if (reduceMotion) FadeSpec else PushSpec)
            isMounted = false
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val p = progress.value
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = if (reduceMotion) 0f else -widthPx * PARALLAX * p }
                .then(if (isMounted) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            content()
        }
        if (isMounted) {
            // Dims what's beneath, and keeps its touches while Settings is up.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (reduceMotion) 0f else (DIM * p).toFloat()))
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } }
                    .clearAndSetSemantics {},
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = if (reduceMotion) 0f else widthPx * (1 - p)
                        alpha = if (reduceMotion) p else 1f
                    },
            ) {
                // iOS `.hidesFromProblemReports()`: Settings shows server credentials.
                HidesFromProblemReports()
                settings()
                // The soft shadow the incoming page casts on what it covers.
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = (-10).dp)
                        .width(10.dp)
                        .fillMaxHeight()
                        .graphicsLayer { alpha = if (reduceMotion) 0f else p }
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.12f))))
                        .clearAndSetSemantics {},
                )
            }
            // Registered with Settings' content, each time Settings mounts, so it is the newest
            // back handler: it takes the gesture over anything Home registered beneath (the
            // session's stack, the drawer), whose handlers would otherwise win and close Settings
            // without following the finger. Settings' own stack (composed just above) handles
            // pushed pages; this one is enabled only at the root. It stays registered through the
            // slide out, so a commit never restarts it mid-gesture.
            PredictiveBackHandler(enabled = isShown && atRoot) { events ->
                try {
                    events.collect { event ->
                        // The page follows the finger: progress 1 is open, 0 is closed.
                        if (!reduceMotion) progress.snapTo(1f - event.progress)
                    }
                    // Committed: close from where the finger left it (isShown → false animates to 0).
                    onClose()
                } catch (cancelled: CancellationException) {
                    // Cancelled: spring back open.
                    scope.launch { progress.animateTo(1f, if (reduceMotion) FadeSpec else PushSpec) }
                    throw cancelled
                }
            }
        }
    }

}

/** How far Home moves left, and how much it dims, under open Settings. */
private const val PARALLAX = 0.3f
private const val DIM = 0.12

/**
 * iOS `.interpolatingSpring(duration: 0.42, bounce: 0)`: critically damped, stiffness (2π / 0.42)²
 * (built per use so Developer Mode slow motion stretches it).
 */
private val PushSpec: AnimationSpec<Float> get() = MotionSpring(duration = 0.42, bounce = 0.0).spec()

/** iOS `.easeInOut(duration: 0.3)` cross-fade under reduce motion. */
private val FadeSpec: AnimationSpec<Float> get() = Motion.easeInOut(0.3)
