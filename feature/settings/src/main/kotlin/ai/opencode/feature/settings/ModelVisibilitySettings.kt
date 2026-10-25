package ai.opencode.feature.settings

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.server.OpenCodeServer

/** STUB: port of iOS `Settings/ModelVisibilitySettings.swift`. */
class ModelVisibilityFeature : Reducer<ModelVisibilityFeature.State, ModelVisibilityFeature.Action> {
    data class State(val server: OpenCodeServer)

    sealed interface Action {
        data object Task : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = Reduced(state)
}
