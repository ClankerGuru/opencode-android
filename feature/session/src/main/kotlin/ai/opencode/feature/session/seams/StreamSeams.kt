package ai.opencode.feature.session.seams

import ai.opencode.server.model.OpenCodeSession

// Voice stand-ins group STREAM (SessionFeature+Stream/Refresh/History/Run) needs from :feature:voice.

/** SEAM: real member belongs to :feature:voice (iOS `VoiceFeature.State.isOnDraft`); delete when that area merges. */
val VoiceFeature.State.isOnDraft: Boolean get() = target.sessionID == null

/**
 * SEAM: real case belongs to :feature:voice (iOS `VoiceFeature.Action.draftSessionCreated(_:)`: a
 * call on a draft moves onto the session its first send created); delete when that area merges.
 */
data class VoiceDraftSessionCreated(val session: OpenCodeSession) : VoiceFeature.Action
