package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeFileReference
import ai.opencode.server.model.OpenCodeOutgoingAttachment

/**
 * A Voice message the server admitted into a session: the voice-owned stand-in
 * for iOS `SessionFeature.PendingPrompt` as voice builds it (`requestEpoch` 0;
 * inline attachments only for a Developer Mode screenshot). Session/Composer sit above voice, so voice
 * hands this to [VoiceAdmissionRecorder] and the app maps it onto its own
 * pending prompt. Path attachments carry [OpenCodeFileReference] (iOS wraps them
 * in `ComposerFileAttachment(uploaded:)`).
 */
data class VoiceAdmittedPrompt(
    val messageID: String,
    val text: String,
    /** A Developer Mode screenshot and app log (`capture_app_screenshot`). */
    val attachments: List<OpenCodeOutgoingAttachment> = emptyList(),
    val files: List<OpenCodeFileReference> = emptyList(),
    val delivery: OpenCodeDelivery = OpenCodeDelivery.Steer,
    /**
     * Voice owns its run separately; remembering its row must not keep a
     * composer's `isAwaitingReply` alive after the server reports idle.
     * (iOS `PromptOutbox.recordAdmitted` sets it false.)
     */
    val ownsRun: Boolean = false,
    val requestEpoch: Int = 0,
)

/**
 * iOS `PromptOutbox.recordAdmitted(_:sessionID:serverKey:)`: a send this phone
 * made outside the outbox (the voice agent's), once the server admitted it, so a
 * screen that opens on its session shows it like any admitted send. The live
 * value is inert; `:app` installs the composer's outbox through
 * `DependencyValues.installRoot`.
 */
class VoiceAdmissionRecorder(
    val recordAdmitted: (prompt: VoiceAdmittedPrompt, sessionID: String, serverKey: String) -> Unit,
) {
    companion object Key : DependencyKey<VoiceAdmissionRecorder> {
        override val liveValue: VoiceAdmissionRecorder = VoiceAdmissionRecorder { _, _, _ -> }
        override val testValue: VoiceAdmissionRecorder get() = VoiceAdmissionRecorder { _, _, _ -> }
    }
}
