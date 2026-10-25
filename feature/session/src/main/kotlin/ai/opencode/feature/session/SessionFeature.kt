package ai.opencode.feature.session

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.combine
import ai.opencode.core.store.scope
import ai.opencode.feature.composer.ComposerFeature
import ai.opencode.feature.transcript.TranscriptFeature

/**
 * Phase 0 placeholder for this area's root reducer (iOS TCA feature).
 *
 * Will port `SessionFeature*.swift`, `SessionView*.swift`, `SessionSheetFeature`, `ShellOutputFeature`, `FolderBrowserFeature.swift`, `BackgroundWorkPanel.swift`, `AgentActivity/`, `SessionExport.swift`
 * from `/Users/vogel/dev/opencode-ios/OpenCode/Session/` (all of: OpenCode/Session/).
 * Track every file in `docs/parity/session.md`.
 *
 * Embeds `:feature:transcript`, `:feature:composer`, `:feature:voice` and `:feature:media` (browser) as child reducers, as iOS `SessionFeature` composes them.
 *
 * Keep the public names (`SessionFeature`, `SessionFeature.State`, `SessionFeature.Action`, `SessionView`) stable: `:app`
 * and parent features already wire them. Replace the placeholder state and actions freely.
 */
class SessionFeature : Reducer<SessionFeature.State, SessionFeature.Action> {
    data class State(
        /** Null for a draft (iOS mints the session ID client-side on first send). */
        val sessionID: String? = null,
        val title: String = "New Session",
        val transcript: TranscriptFeature.State = TranscriptFeature.State(),
        val composer: ComposerFeature.State = ComposerFeature.State(),
    )

    sealed interface Action {
        /** The view appeared (TCA `.task`). */
        data object Task : Action
        data class Transcript(val action: TranscriptFeature.Action) : Action
        data class Composer(val action: ComposerFeature.Action) : Action
    }

    private val body: Reducer<State, Action> = combine(
        scope(
            state = State::transcript,
            setState = { s, c -> s.copy(transcript = c) },
            action = { (it as? Action.Transcript)?.action },
            embed = Action::Transcript,
            child = TranscriptFeature(),
        ),
        scope(
            state = State::composer,
            setState = { s, c -> s.copy(composer = c) },
            action = { (it as? Action.Composer)?.action },
            embed = Action::Composer,
            child = ComposerFeature(),
        ),
        Reducer { state, action ->
            when (action) {
                Action.Task -> Reduced(state)
                is Action.Transcript, is Action.Composer -> Reduced(state)
            }
        },
    )

    override fun reduce(state: State, action: Action): Reduced<State, Action> = body.reduce(state, action)
}
