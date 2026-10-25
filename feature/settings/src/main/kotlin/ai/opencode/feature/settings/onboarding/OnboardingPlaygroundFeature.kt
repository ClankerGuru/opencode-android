package ai.opencode.feature.settings.onboarding

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer

/** STUB: port of iOS `Settings/Onboarding/OnboardingPlaygroundFeature.swift` (being ported). */
class OnboardingPlaygroundFeature : Reducer<OnboardingPlaygroundFeature.State, OnboardingPlaygroundFeature.Action> {
    data class State(val placeholder: Boolean = true)

    sealed interface Action {
        data object Task : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = Reduced(state)
}
