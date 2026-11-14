package ai.opencode.feature.media

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.store.rememberScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * The media area's root composable: draws [content] (the session) and, above it, whichever
 * full-screen viewer is up: the transcript image viewer from [MediaFeature], or a viewer presented
 * through [LocalMediaPresenter] (files, diagrams, pasted text). While one is up the content below
 * is hidden from TalkBack and takes no touches.
 *
 * The overlay covers this composable's bounds: place it over the whole window so flights
 * start on the thumbnails' exact window frames.
 */
@Composable
fun MediaView(
    store: StoreOf<MediaFeature.State, MediaFeature.Action>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    LaunchedEffect(store) {
        store.send(MediaFeature.Action.Task)
        // `adb shell am start --ez lab-media-trace true …`.
        if (context.findActivity()?.intent?.getBooleanExtra("lab-media-trace", false) == true) {
            MediaTransitionTrace.isEnabled = true
        }
    }
    val presenter = remember(store) {
        MediaPresenter().apply {
            imageViewerIsUp = { store.state.viewer != null }
            sendImage = { store.send(MediaFeature.Action.PresentImage(it)) }
        }
    }
    val viewerID by store.observe { it.viewer?.preview?.id }
    val presentation = presenter.presentation
    val reducesMotion = rememberReduceMotion()
    val presented = viewerID != null || presentation != null

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (presented) Modifier.clearAndSetSemantics { } else Modifier)) {
            CompositionLocalProvider(LocalMediaPresenter provides presenter, content = content)
        }
        val id = viewerID
        if (id != null) {
            key(id) {
                // The last viewer state, so the scope never reads `null` while its dismissal lands.
                val last = remember { LastViewer(store.state.viewer!!) }
                val viewerStore = store.rememberScope<MediaFeature.State, MediaFeature.Action, TranscriptImageViewerFeature.State, TranscriptImageViewerFeature.Action>(
                    state = { state -> state.viewer?.also { last.value = it } ?: last.value },
                    action = { MediaFeature.Action.Viewer(PresentationAction.Presented(it)) },
                )
                MediaViewerHost(
                    sourceID = id,
                    isMedia = true,
                    reducesMotion = reducesMotion,
                    screenName = stringResource(R.string.media_viewer_screen_title),
                    onDismissed = { store.send(MediaFeature.Action.Viewer(PresentationAction.Dismiss)) },
                ) { viewer ->
                    TranscriptImageViewer(viewerStore, viewer)
                }
            }
        } else if (presentation != null) {
            key(presentation) {
                MediaViewerHost(
                    sourceID = presentation.sourceID,
                    isMedia = presentation.isMedia,
                    reducesMotion = presentation.reducesMotion,
                    screenName = presentation.screenName,
                    onDismissed = {
                        if (presenter.presentation === presentation) presenter.presentation = null
                        presentation.onDismiss?.invoke()
                    },
                    flightAccessories = presentation.flightAccessories,
                    content = presentation.content,
                )
            }
        }
    }
}

private class LastViewer(var value: TranscriptImageViewerFeature.State)
