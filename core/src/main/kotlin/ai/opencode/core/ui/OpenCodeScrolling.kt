package ai.opencode.core.ui

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/*
 * A page whose content fits on screen doesn't move when dragged, as Android's views do
 * (`OVER_SCROLL_IF_CONTENT_SCROLLS`): no large title collapsing, no stretch. Compose's lists
 * otherwise stretch, and pass the drag on to the title bar, even with nothing to scroll.
 */

/** Whether [state]'s content is taller than its viewport, so a drag has something to scroll. */
@Composable
fun rememberContentScrolls(state: ScrollableState): State<Boolean> =
    remember(state) { derivedStateOf { state.canScrollForward || state.canScrollBackward } }

/**
 * The platform's stretch overscroll, only while [state]'s content can scroll. Pass it as a
 * lazy list's `overscrollEffect` (or to `Modifier.verticalScroll`).
 */
@Composable
fun rememberOverscrollIfContentScrolls(state: ScrollableState): OverscrollEffect? {
    val effect = rememberOverscrollEffect()
    val scrolls by rememberContentScrolls(state)
    if (!scrolls) return null
    return effect
}

/**
 * [OpenCodeLargeTopBar]'s scroll behaviour: Material's exit-until-collapsed, only while the
 * page's content ([state]) can scroll, so a page that fits keeps its large title. A collapsed
 * bar can always expand again, even if the page fits once the title is gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberOpenCodeLargeTopBarScrollBehavior(state: ScrollableState): TopAppBarScrollBehavior {
    val barState = rememberTopAppBarState()
    return TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = barState,
        canScroll = { state.canScrollForward || state.canScrollBackward || barState.collapsedFraction > 0f },
    )
}
