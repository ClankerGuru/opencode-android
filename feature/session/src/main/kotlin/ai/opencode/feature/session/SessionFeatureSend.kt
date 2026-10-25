package ai.opencode.feature.session

import ai.opencode.core.store.Reduced
import ai.opencode.feature.session.seams.ComposerAutocompleteChip
import ai.opencode.feature.session.seams.ComposerFileAttachment
import ai.opencode.feature.session.seams.PageShareRequest
import ai.opencode.server.model.OpenCodeAutocompleteMentions
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeOutgoingAttachment

// Ported by group SEND (iOS `SessionFeature+Send.swift`).

/** iOS `SessionFeature.PendingPrompt`: one send, as the outbox delivers it. */
data class PendingPrompt(
    val messageID: String,
    val text: String,
    val attachments: List<OpenCodeOutgoingAttachment>,
    val files: List<ComposerFileAttachment> = emptyList(),
    val wasRunning: Boolean = false,
    /** Voice owns its run separately; remembering its row must not keep a composer's `isAwaitingReply` alive after the server reports idle. */
    val ownsRun: Boolean = true,
    val delivery: OpenCodeDelivery = OpenCodeDelivery.Steer,
    val requestEpoch: Int,
    /** Applied (`POST …/model`) just before the prompt, as the TUI does: a pick made since the last send, or a draft's model. */
    val model: OpenCodeModelRef? = null,
    /** Agent Selection: applied (`POST …/agent`) just before the prompt, like [model], when the session doesn't have it yet. */
    val agent: String? = null,
    /** Sent from the in-app browser's open page: read at delivery and folded into the body (`PromptPageContext`). */
    val page: PageShareRequest? = null,
    /** Structured autocomplete mentions folded into `PromptBody`. */
    val mentions: OpenCodeAutocompleteMentions? = null,
    /** Chosen autocomplete chips carried with the prompt for edit/restore. */
    val chips: List<ComposerAutocompleteChip> = emptyList(),
) {
    /** A queued send posted mid-run: the run it waits behind keeps its model. */
    val waitsBehindRun: Boolean get() = wasRunning && delivery == OpenCodeDelivery.Queue
}

/** Sends, failed sends, run errors, and undo. Null: the action passes on. */
internal fun SessionFeature.reduceSending(
    state: SessionFeature.State,
    action: SessionFeature.Action,
): Reduced<SessionFeature.State, SessionFeature.Action>? = null // TODO(group SEND)

/**
 * iOS `restoreOutbox(_:)`: lays this phone's own sends from the outbox over the screen.
 * TODO(group SEND): port; `func restoreOutbox(_ state: inout State) -> Effect<Action>`.
 */
internal fun SessionFeature.restoreOutbox(
    state: SessionFeature.State,
): Reduced<SessionFeature.State, SessionFeature.Action> = Reduced(state)
