package ai.opencode.feature.session

import ai.opencode.core.store.Reduced
import ai.opencode.feature.session.seams.TranscriptEntry
import ai.opencode.server.model.OpenCodeTranscriptItem

// Ported by group STREAM (iOS `SessionFeature+Stream.swift`).

/** The event stream, transcript refreshes, and the screen's lifecycle. Null: the action passes on. */
internal fun SessionFeature.reduceStream(
    state: SessionFeature.State,
    action: SessionFeature.Action,
): Reduced<SessionFeature.State, SessionFeature.Action>? = null // TODO(group STREAM)

/**
 * iOS `SessionFeature.messageID(for:)`: the server message an item belongs to. Ported here early
 * because `+State` needs it; group STREAM owns it.
 */
internal fun SessionFeature.Companion.messageID(item: OpenCodeTranscriptItem): String {
    TranscriptEntry.promptID(item)?.let { return it }
    return when (item) {
        is OpenCodeTranscriptItem.Tool ->
            item.call.messageID ?: item.id.indexOf("-tool-").takeIf { it >= 0 }?.let { item.id.substring(0, it) } ?: item.id
        is OpenCodeTranscriptItem.AssistantMarkdown ->
            item.id.lastIndexOf("-text-").takeIf { it >= 0 }?.let { item.id.substring(0, it) } ?: item.id
        // An assistant message's error carries its ID with `-error`; an idle message's is its own.
        is OpenCodeTranscriptItem.RunError -> if (item.error.beforeReply) item.id else item.id.dropLast("-error".length)
        is OpenCodeTranscriptItem.RunRetry -> item.id.dropLast("-retry".length)
        else -> item.id
    }
}

/** iOS `SessionFeature.promptIDs(in:)`. */
internal fun SessionFeature.Companion.promptIDs(items: List<OpenCodeTranscriptItem>): Set<String> =
    items.mapNotNullTo(HashSet()) { TranscriptEntry.promptID(it) }
