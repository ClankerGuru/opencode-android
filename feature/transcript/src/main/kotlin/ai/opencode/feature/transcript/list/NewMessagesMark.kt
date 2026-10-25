package ai.opencode.feature.transcript.list

import ai.opencode.feature.transcript.NewMessagesPlacement
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.rows.TranscriptTimeStamps
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.runtime.Immutable

/**
 * What arrived while the reader was away (iOS `NewMessagesMark`): from the first row that wasn't on
 * screen when they last left the session. The list brings them to it at the end; with the New
 * Messages Divider experiment on, a New divider sits above it when it doesn't fit on screen.
 *
 * The session-state half of iOS's file (`SessionFeature.State.noteLeaving/noteOpening/
 * noteOwnSend/absorbFittingNewMessages/updateNewMessagesMark`, `newMessagesContext = 20`) is
 * reducer logic and belongs to `:feature:session`; it builds marks with [firstUnseen].
 */
@Immutable
data class NewMessagesMark(
    /** The first transcript item the reader hadn't seen. */
    val itemID: String,
    /** The last item it covered: more arriving below makes a new mark. */
    val lastItemID: String? = null,
    /** Changes whenever the divider moves to another item, so the list lands on each mark once. */
    val generation: Int,
    /** The New Messages Divider experiment was on. */
    val showsDivider: Boolean = true,
) {
    companion object {
        /** Rows kept above the New divider (iOS `SessionFeature.State.newMessagesContext`). */
        const val NEW_MESSAGES_CONTEXT = 20

        /**
         * The first item in [items] that isn't in [seen], or null when nothing is new. Client-side
         * rows (`info-` notices, send failures) never count, nor do the reader's own messages:
         * sending one reads everything above it. Messages an agent sent (`source`) are news.
         */
        fun firstUnseen(items: List<OpenCodeTranscriptItem>, seen: Set<String>): String? {
            if (seen.isEmpty()) return null
            // After the last row they saw: older rows paged in or a notice in an earlier turn aren't news.
            val afterSeen = items.indexOfLast { it.id in seen }.let { if (it >= 0) it + 1 else 0 }
            val agentPrompts = items.mapNotNull { (it as? OpenCodeTranscriptItem.UserText)?.takeIf { t -> t.source != null }?.id }.toSet()
            fun isOwn(item: OpenCodeTranscriptItem) = TranscriptEntry.promptID(item)?.let { it !in agentPrompts } ?: false
            val tail = items.subList(afterSeen, items.size)
            val lastOwn = tail.indexOfLast(::isOwn)
            val start = if (lastOwn >= 0) afterSeen + lastOwn + 1 else afterSeen
            return items.subList(start, items.size).firstOrNull { item ->
                if (item.id in seen || item.id.startsWith("info-")) return@firstOrNull false
                item !is OpenCodeTranscriptItem.SendFailure && item !is OpenCodeTranscriptItem.RunRetry &&
                    item !is OpenCodeTranscriptItem.Undone
            }?.id
        }

        /** Where the list draws the New divider: above the first entry holding an item from the mark onward. */
        fun resolve(mark: NewMessagesMark?, items: List<OpenCodeTranscriptItem>, entries: List<TranscriptEntry>): NewMessagesPlacement? {
            if (mark == null) return null
            val start = items.indexOfFirst { it.id == mark.itemID }
            if (start < 0) return null
            val newIDs = items.subList(start, items.size).map { it.id }.toSet()
            // An entry that starts with something new: a group already on screen stays above.
            var index = entries.indexOfFirst { e -> e.itemIDs.firstOrNull()?.let { it in newIDs } ?: false }
            if (index < 0) return null
            // A message dated with a time just above it: the divider goes above both.
            val previous = entries.getOrNull(index - 1)
            if (previous is TranscriptEntry.Single && previous.item is OpenCodeTranscriptItem.Info &&
                previous.item.id.startsWith(TranscriptTimeStamps.PREFIX)
            ) {
                index--
            }
            return NewMessagesPlacement(entries[index].id, mark.generation, mark.showsDivider)
        }
    }
}
