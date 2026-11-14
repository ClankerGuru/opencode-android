package ai.opencode.feature.media

import ai.opencode.core.motion.rememberReduceMotion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics

/** One fullscreen presentation host, hiding the underlying session from TalkBack while open. */
@Composable
fun MediaView(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    LaunchedEffect(context) {
        // `adb shell am start --ez lab-media-trace true …`.
        if (context.findActivity()?.intent?.getBooleanExtra("lab-media-trace", false) == true) {
            MediaTransitionTrace.isEnabled = true
        }
    }
    val reducedMotion = rememberReduceMotion()
    val presenter = remember { MediaPresenter() }
    presenter.reducesMotion = reducedMotion
    val presentation = presenter.presentation

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (presentation != null) Modifier.clearAndSetSemantics { } else Modifier)) {
            CompositionLocalProvider(LocalMediaPresenter provides presenter, content = content)
        }
        if (presentation != null) {
            key(presentation) {
                DisposableEffect(presentation) { onDispose { presentation.onDismiss?.invoke() } }
                MediaViewerHost(
                    sourceID = presentation.sourceID,
                    isMedia = presentation.isMedia,
                    reducesMotion = presentation.reducesMotion,
                    screenName = presentation.screenName,
                    onDismissed = {
                        if (presenter.presentation === presentation) presenter.presentation = null
                    },
                    flightAccessories = presentation.flightAccessories,
                    content = presentation.content,
                )
            }
        }
    }
}
