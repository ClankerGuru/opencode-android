package ai.opencode.feature.drawer.list

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

private val neverHidden: State<Boolean> = mutableStateOf(false)

/**
 * The drawer is composed but can't be seen (iOS f5636460: the drawer builds nothing while
 * hidden). The container keeps the drawer composed once it has shown (and prewarms it after
 * launch), so the list and its rings read this to stop rebuilding and spinning until it shows.
 * A [State] so a flip recomposes only the readers.
 */
val LocalDrawerHidden = staticCompositionLocalOf { neverHidden }
