package ai.opencode.feature.session.seams

import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.feature.session.SessionFeature
import ai.opencode.server.model.OpenCodeSession

// Stand-ins group STREAM (SessionFeature+Stream/Refresh/History/Run) needs from areas not ported yet.

/**
 * SEAM: real member belongs to :feature:composer (iOS `Composer/DictationClient.swift`
 * `DictationFeature.discardPending(_:dictation:)`); delete when that area merges. An answer's
 * recording carried from the last screen ends when this screen's task starts.
 */
fun DictationFeature.Companion.discardPending(
    state: DictationFeature.State,
    dictation: DictationClient,
): Reduced<DictationFeature.State, DictationFeature.Action> {
    val discarded = state.discarded ?: return Reduced(state)
    return Reduced(
        state.copy(discarded = null),
        Effect.merge(
            Effect.cancel(DictationRecordingCancelID(discarded.recording)),
            Effect.run { dictation.cancel(discarded.press) },
        ),
    )
}

/**
 * SEAM: real member belongs to :feature:composer (iOS `DictationClient.prepare`: warms the
 * recognizer so the first press starts at once); delete when that area merges. Does nothing.
 */
@Suppress("UnusedReceiverParameter")
suspend fun DictationClient.prepare() = Unit

/** SEAM: real member belongs to :feature:voice (iOS `VoiceFeature.State.isOnDraft`); delete when that area merges. */
val VoiceFeature.State.isOnDraft: Boolean get() = target.sessionID == null

/**
 * SEAM: real case belongs to :feature:voice (iOS `VoiceFeature.Action.draftSessionCreated(_:)`: a
 * call on a draft moves onto the session its first send created); delete when that area merges.
 */
data class VoiceDraftSessionCreated(val session: OpenCodeSession) : VoiceFeature.Action

/**
 * SEAM: real member belongs to :feature:composer (iOS `ComposerPersistenceClient
 * .promoteDraftOutboxToSession(_:_:_:)`: a draft's saved outbox moves under the session it
 * created); delete when that area merges. Does nothing.
 */
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun ComposerPersistenceClient.promoteDraftOutboxToSession(serverID: String, draftID: String?, sessionID: String) = Unit
