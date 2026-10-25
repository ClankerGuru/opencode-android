package ai.opencode.feature.session.seams

// Voice stand-ins the MODELS group (iOS `SessionFeature+Models/Agents/Dictation/Attachments/Browser`)
// needs from :feature:voice, which this module doesn't take yet.

// region Voice (Voice/VoiceFeature.swift)

/** SEAM: iOS `VoiceFeature.State.Phase.isTerminal`; delete when :feature:voice merges. */
val VoiceFeature.State.Phase.isTerminal: Boolean
    get() = this == VoiceFeature.State.Phase.Ended || this is VoiceFeature.State.Phase.Failed || this == VoiceFeature.State.Phase.Dropped

/**
 * SEAM: iOS `VoiceFeature.State.moveDraft(to:)`; delete when :feature:voice merges. The draft the call
 * is on changed project: the same conversation, created somewhere else.
 */
fun VoiceFeature.State.moveDraft(directory: String): VoiceFeature.State {
    if (target.sessionID != null || target.directory == directory) return this
    return copy(draftSessionID = null, target = target.copy(directory = directory))
}

// endregion
