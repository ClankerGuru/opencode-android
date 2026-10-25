package ai.opencode.feature.composer

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer

/**
 * Phase 0 placeholder for this area's root reducer (iOS TCA feature).
 *
 * Will port `DictationFeature`, `ComposerAttachmentsFeature`, `RequestDockFeature`, `ModelPicker/`, `PlusMenu/`, `PromptOutbox`, `ComposerTextInput.swift`, `MicPressGesture.swift`, `AttachmentCameraView.swift`
 * from `/Users/vogel/dev/opencode-ios/OpenCode/Composer/` (all of: OpenCode/Composer/).
 * Track every file in `docs/parity/composer.md`.
 *
 * Keep the public names (`ComposerFeature`, `ComposerFeature.State`, `ComposerFeature.Action`, `ComposerView`) stable: `:app`
 * and parent features already wire them. Replace the placeholder state and actions freely.
 */
class ComposerFeature : Reducer<ComposerFeature.State, ComposerFeature.Action> {
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
