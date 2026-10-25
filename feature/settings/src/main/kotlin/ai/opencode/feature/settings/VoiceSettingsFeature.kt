package ai.opencode.feature.settings

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.server.OpenCodeServer

/** STUB: port of iOS `VoiceSettingsFeature` (`Settings/SettingsView.swift`). */
class VoiceSettingsFeature : Reducer<VoiceSettingsFeature.State, VoiceSettingsFeature.Action> {
    data class State(val server: OpenCodeServer? = null)

    sealed interface Action {
        data object Task : Action
        data object VoicePickerDisappeared : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = Reduced(state)
}
