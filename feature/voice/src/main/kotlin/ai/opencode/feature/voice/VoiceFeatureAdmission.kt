package ai.opencode.feature.voice

import ai.opencode.core.store.Send
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Keep every voice engine's accepted sends visible both now and on entry
 * (iOS `VoiceFeature.recordAdmission`, `VoiceFeature+Admission.swift`). iOS records a
 * `SessionFeature.PendingPrompt` in `PromptOutbox`; voice hands a [VoiceAdmittedPrompt] to
 * [VoiceAdmissionRecorder], which the app installs over the composer's outbox.
 */
internal suspend fun recordAdmission(
    prompt: VoiceAdmittedPrompt,
    sessionID: String,
    serverKey: String,
    outbox: VoiceAdmissionRecorder,
    send: Send<VoiceFeature.Action>,
) {
    // Even a call cancelled during submission leaves its accepted prompt.
    outbox.recordAdmitted(prompt, sessionID, serverKey)
    if (!currentCoroutineContext().isActive) return
    send(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.PromptAdmitted(sessionID)))
}
