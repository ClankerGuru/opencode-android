package ai.opencode.feature.voice

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer

/**
 * How far the floating call has grown (iOS `FloatingCallStage`, One Shape): the orb, a card
 * above the composer, or a sheet the height of the screen. One shape grows through all
 * three on one vertical drag.
 */
enum class FloatingCallStage {
    orb, card, full;

    /** One stage down, or null at the orb. */
    val previous: FloatingCallStage? get() = entries.getOrNull(ordinal - 1)
}

/**
 * Port of iOS `FloatingCallStageFeature`: which stage the floating call settles on, and the
 * haptics for getting there. The per-frame motion between stages is the view's; this only
 * hears where a tap, a drag or its release lands.
 */
class FloatingCallStageFeature : Reducer<FloatingCallStageFeature.State, FloatingCallStageFeature.Action> {
    data class State(
        val stage: FloatingCallStage = FloatingCallStage.orb,
        /** The stage a drag in progress would settle on if let go now. */
        val dragTarget: FloatingCallStage? = null,
    )

    sealed interface Action {
        /** The orb was tapped: it grows into the card. */
        data object OrbTapped : Action

        /** The chevron: straight back to the orb. */
        data object MinimizeTapped : Action

        /** TalkBack's escape: one stage down. */
        data object StepBack : Action
        data object DragBegan : Action

        /** The finger crossed into the reach of another stage. */
        data class DragCrossed(val stage: FloatingCallStage) : Action

        /** Let go: where the release (and its speed) carries it. */
        data class DragEnded(val stage: FloatingCallStage) : Action

        /** The playground opened or closed it. */
        data class Requested(val stage: FloatingCallStage) : Action

        /**
         * A pending request docked in the composer: the full sheet steps down to the card,
         * above the composer, so the request shows.
         */
        data object RequestDocked : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val haptics = dependency(HapticsClient)
        return when (action) {
            Action.OrbTapped -> {
                if (state.stage != FloatingCallStage.orb || state.dragTarget != null) return Reduced(state)
                haptics.play(Haptic.tap)
                Reduced(state.copy(stage = FloatingCallStage.card))
            }
            Action.MinimizeTapped -> Reduced(state.copy(dragTarget = null, stage = FloatingCallStage.orb))
            Action.StepBack -> {
                val previous = state.stage.previous ?: return Reduced(state)
                Reduced(state.copy(dragTarget = null, stage = previous))
            }
            Action.DragBegan -> Reduced(state.copy(dragTarget = state.stage))
            is Action.DragCrossed -> {
                val target = state.dragTarget
                if (target == null || target == action.stage) return Reduced(state)
                haptics.play(Haptic.threshold)
                Reduced(state.copy(dragTarget = action.stage))
            }
            is Action.DragEnded -> {
                val cleared = state.copy(dragTarget = null)
                if (action.stage == state.stage) return Reduced(cleared)
                haptics.play(Haptic.snap)
                Reduced(cleared.copy(stage = action.stage))
            }
            is Action.Requested -> Reduced(state.copy(dragTarget = null, stage = action.stage))
            Action.RequestDocked -> {
                if (state.stage != FloatingCallStage.full || state.dragTarget != null) return Reduced(state)
                Reduced(state.copy(stage = FloatingCallStage.card))
            }
        }
    }
}
