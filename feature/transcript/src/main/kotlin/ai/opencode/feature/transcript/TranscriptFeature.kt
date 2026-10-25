package ai.opencode.feature.transcript

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer

/**
 * Phase 0 placeholder for this area's root reducer (iOS TCA feature).
 *
 * Will port `TranscriptListViewController*.swift` (list engine → LazyColumn + plain scroll-follow objects), `TranscriptEntry*.swift`, `OpenCodeMarkdownBlock*.swift`, `MarkdownStream*.swift`, `MathRendering.swift`, `RunError*.swift`
 * from `/Users/vogel/dev/opencode-ios/OpenCode/Transcript/` (all of: OpenCode/Transcript/).
 * Track every file in `docs/parity/transcript.md`.
 *
 * iOS has no TranscriptFeature reducer: row UI state (`TranscriptDisclosureState`) lives with the list. Keep this reducer for disclosure/row state only; durable transcript state stays in `SessionFeature`. Rows take immutable value props plus an action lambda, never a store.
 *
 * Keep the public names (`TranscriptFeature`, `TranscriptFeature.State`, `TranscriptFeature.Action`, `TranscriptView`) stable: `:app`
 * and parent features already wire them. Replace the placeholder state and actions freely.
 */
class TranscriptFeature : Reducer<TranscriptFeature.State, TranscriptFeature.Action> {
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
