package ai.opencode.feature.media

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer

/**
 * Phase 0 placeholder for this area's root reducer (iOS TCA feature).
 *
 * Will port `Media/` (viewer, zoom/dismiss, `AgentFiles/`, `LinkPreviews/`, `Mermaid/`) and `Browser/` (`BrowserFeature`, `BrowserWebViews`, page reader)
 * from `/Users/vogel/dev/opencode-ios/OpenCode/Media/` (all of: OpenCode/Media/, OpenCode/Browser/).
 * Track every file in `docs/parity/media.md`.
 *
 * The experimental in-app browser (`BrowserFeature`) lives here too; gate it behind Experimental like iOS.
 *
 * Keep the public names (`MediaFeature`, `MediaFeature.State`, `MediaFeature.Action`, `MediaView`) stable: `:app`
 * and parent features already wire them. Replace the placeholder state and actions freely.
 */
class MediaFeature : Reducer<MediaFeature.State, MediaFeature.Action> {
    data class State(
        val placeholder: Boolean = true,
    )

    sealed interface Action {
        /** The view appeared (TCA `.task`). */
        data object Task : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> Reduced(state)
    }
}
