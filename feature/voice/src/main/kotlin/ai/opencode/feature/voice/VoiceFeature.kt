package ai.opencode.feature.voice

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer

/**
 * Phase 0 placeholder for this area's root reducer (iOS TCA feature).
 *
 * Will port `VoiceFeature.swift`, `VoiceView.swift`, `LiveCallCoordinator`, `VoiceSessionCoordinator`, `LiveTransportChoice`, GPT-Live WebRTC and Realtime engines, `CallVoice.swift`, `VoiceTranscript.swift`, `ServerVoiceClient.swift`
 * from `/Users/vogel/dev/opencode-ios/OpenCode/Voice/` (all of: OpenCode/Voice/).
 * Track every file in `docs/parity/voice.md`.
 *
 * Plain objects own audio meters and per-frame visuals (`VoiceAudioLevels`, dot scope); the reducer gets coarse `VoiceCallEvent`s.
 *
 * Keep the public names (`VoiceFeature`, `VoiceFeature.State`, `VoiceFeature.Action`, `VoiceView`) stable: `:app`
 * and parent features already wire them. Replace the placeholder state and actions freely.
 */
class VoiceFeature : Reducer<VoiceFeature.State, VoiceFeature.Action> {
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
