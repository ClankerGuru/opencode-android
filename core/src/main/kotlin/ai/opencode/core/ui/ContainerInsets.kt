package ai.opencode.core.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * How far the window's edges lie outside the container the content is laid out in: a Catch Up
 * card sits between the system bars plus its header and footer, so the keyboard reaches only
 * part of the way into it. iOS gets this for free (UIKit intersects every view's safe area and
 * keyboard with the view's own frame); Compose's `WindowInsets` are the window's.
 *
 * A container that insets its content provides how much of the window's insets it already
 * covers, and also consumes them (`consumeWindowInsets`) so `imePadding()` and
 * `windowInsetsPadding()` inside it stop at its edges. Insets read directly (`getBottom`) don't
 * see consumption, so code that lays out against the container's edges reads them through
 * [inContainer]. Code that works in window coordinates (overlays placed by `boundsInWindow`)
 * keeps the window's insets.
 *
 * Full-screen content provides nothing: [inContainer] is then the insets themselves.
 */
val LocalContainerInsets = staticCompositionLocalOf { NoContainerInsets }

private val NoContainerInsets = WindowInsets(0, 0, 0, 0)

/** These insets as they reach into the current container: less what lies outside it ([LocalContainerInsets]). */
@Composable
fun WindowInsets.inContainer(): WindowInsets {
    val outside = LocalContainerInsets.current
    if (outside === NoContainerInsets) return this
    return exclude(outside)
}

/** The least room [bottomSafeArea] leaves under content resting on the bottom edge. */
val MinimumBottomSafeArea = 8.dp

/**
 * The navigation bar's bottom inset, but never less than [MinimumBottomSafeArea]: what content
 * resting on the bottom edge (the composer) stands on, like iOS's bottom safe area. Android-only:
 * every iPhone this app runs on has a 34 pt home-indicator safe area, but HyperOS with the
 * gesture hint hidden (and other phones) reports a 0 navigation bar, which left the composer
 * flush on the screen's edge. Gesture navigation (24 dp) and three-button navigation (48 dp) are
 * taller than the floor, so they're unchanged. Code that places things against the composer's
 * resting position reads this, not `navigationBars`, so it doesn't drift by the floor.
 */
val WindowInsets.Companion.bottomSafeArea: WindowInsets
    @Composable get() = navigationBars.union(WindowInsets(bottom = MinimumBottomSafeArea))
