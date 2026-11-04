package ai.opencode.core.ui

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * iOS `ScreenCoveredTrait`: another screen covers this one without it leaving the screen
 * (Settings or What's New over Home at rest, iOS `SettingsCover`). The host provides `true` while
 * the cover rests over it; continuous animations beneath read it to pause, never by polling.
 *
 * iOS's `ViewVisibilityObserver` (KVO on every ancestor layer's hidden/opacity, because UIKit's
 * page stack and Catch Up hide views without telling SwiftUI) has no Compose counterpart to port:
 * a page Compose doesn't draw leaves composition and stops its animations itself.
 */
val LocalScreenCovered: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }
