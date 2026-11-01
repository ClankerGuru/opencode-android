package ai.opencode.feature.session

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.feature.session.SessionFeature.State.EarlierHistory
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.list.NewMessagesMark
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.model.OpenCodeTranscriptItem

// The `SessionFeature.State` members of iOS `Transcript/NewMessagesMark.swift`, with the two
// State-side types it declares (`NewMessagesEarlierBaseline`, `NewMessagesFold`). They extend the
// session's state, so they live in this module; `NewMessagesMark` itself (and the list's
// `NewMessagesPlacement`) belong to `:feature:transcript`.
//
// Bridge: iOS's `NewMessagesMark.firstUnseen(in:seen:watched:viewedMilliseconds:unread:)`,
// `NewMessagesMark.ownPrompts(in:)`, `NewMessagesMark.recordSpan` and
// `TranscriptTimeStamps.isAtOrBefore` belong to `:feature:transcript`; until that module has
// them, [NewMessagesUnseen] holds a port of each here.

/**
 * iOS `NewMessagesEarlierBaseline`: unseen baseline kept when a settled mark starts at the top of a
 * paged window (`earlierHistory != Exhausted`), so loading an earlier page can move the divider to
 * the true first unseen row.
 */
data class NewMessagesEarlierBaseline(
    val seen: Set<String>,
    val watched: Set<String>,
    val viewedMilliseconds: Long?,
    val unread: Boolean,
)

/** iOS `NewMessagesFold`: new rows from [itemID] through [lastItemID] (of [count] rows) that start no entry of their own. */
data class NewMessagesFold(
    val itemID: String,
    val lastItemID: String?,
    val count: Int,
) {
    companion object {
        /**
         * Live events that only add text or tool input, never changing which rows start an entry (a
         * tool's name or status: a question answered joins the transcript), so a fold found before
         * them still holds.
         */
        fun keepsShapes(events: List<OpenCodeLiveContentEvent>): Boolean = events.all {
            when (it) {
                is OpenCodeLiveContentEvent.StepStarted, is OpenCodeLiveContentEvent.StepStreamed,
                is OpenCodeLiveContentEvent.StepEnded, is OpenCodeLiveContentEvent.TextStarted,
                is OpenCodeLiveContentEvent.TextDelta, is OpenCodeLiveContentEvent.TextEnded,
                is OpenCodeLiveContentEvent.ReasoningStarted, is OpenCodeLiveContentEvent.ReasoningDelta,
                is OpenCodeLiveContentEvent.ReasoningEnded, is OpenCodeLiveContentEvent.ToolInputDelta,
                is OpenCodeLiveContentEvent.ToolInputEnded,
                -> true
                is OpenCodeLiveContentEvent.ToolInputStarted, is OpenCodeLiveContentEvent.ToolCalled,
                is OpenCodeLiveContentEvent.ToolProgress, is OpenCodeLiveContentEvent.ToolSucceeded,
                is OpenCodeLiveContentEvent.ToolFailed,
                -> false
            }
        }
    }
}

/** The transcript-side helpers iOS keeps on `NewMessagesMark` and `TranscriptTimeStamps` (see the file note). */
object NewMessagesUnseen {
    /**
     * iOS `NewMessagesMark.firstUnseen(in:seen:watched:viewedMilliseconds:unread:)`: the first item
     * in [items] that isn't in [seen], or null when nothing is new. Client-side rows (`info-`
     * notices, send failures) never count as new, nor do the reader's own messages, from this phone
     * or another client: like a chat app, sending one reads everything above it. Messages an agent
     * sent into the session (`source`) are news. Rows in [watched] streamed in while the reader
     * watched: seen, though rows a read brings in above them may not have been.
     */
    fun firstUnseen(
        items: List<OpenCodeTranscriptItem>,
        seen: Set<String>,
        watched: Set<String> = emptySet(),
        viewedMilliseconds: Long? = null,
        unread: Boolean = false,
    ): String? {
        val hasTimedAfterViewed = viewedMilliseconds?.let { cutoff ->
            items.any { isAtOrBefore(cutoff, it.id) == false }
        } ?: false
        if (seen.isEmpty() && !unread && !hasTimedAfterViewed) return null
        fun isSeen(item: OpenCodeTranscriptItem): Boolean {
            if (item.id in seen) return true
            return viewedMilliseconds != null && isAtOrBefore(viewedMilliseconds, item.id) == true
        }
        // After the last row they saw: older rows paged in or a notice placed in an earlier turn
        // aren't news.
        val afterSeen = items.indexOfLast(::isSeen).let { if (it >= 0) it + 1 else 0 }
        val tail = items.subList(afterSeen, items.size)
        val isOwn = ownPrompts(tail)
        val start = tail.indexOfLast(isOwn).let { if (it >= 0) afterSeen + it + 1 else afterSeen }
        return items.subList(start, items.size).firstOrNull { item ->
            if (isSeen(item) || item.id in watched || item.id.startsWith("info-")) return@firstOrNull false
            item !is OpenCodeTranscriptItem.SendFailure && item !is OpenCodeTranscriptItem.RunRetry &&
                item !is OpenCodeTranscriptItem.Undone
        }?.id
    }

    /** iOS `NewMessagesMark.ownPrompts(in:)`: whether a row is part of the reader's own message, from any client; a message an agent sent into the session isn't theirs. */
    fun ownPrompts(items: Iterable<OpenCodeTranscriptItem>): (OpenCodeTranscriptItem) -> Boolean {
        val agentPrompts = items.mapNotNullTo(HashSet()) { item ->
            (item as? OpenCodeTranscriptItem.UserText)?.takeIf { it.source != null }?.id
        }
        return { item -> TranscriptEntry.promptID(item)?.let { it !in agentPrompts } ?: false }
    }

    /** iOS `TranscriptTimeStamps.isAtOrBefore(_:id:)`: whether [id] carries an OpenCode timestamp minted at or before [milliseconds]. */
    fun isAtOrBefore(milliseconds: Long, id: String): Boolean? {
        if (milliseconds <= 0) return null
        val value = parsedValue(id) ?: return null
        val mask = 0xF_FFFF_FFFFL
        val itemMS = (value ushr 12) and mask
        val cutoffMS = milliseconds and mask
        val ahead = (itemMS - cutoffMS) and mask
        return ahead == 0L || ahead > (mask ushr 1)
    }

    /** iOS `TranscriptTimeStamps.parsedValue(ofID:)`: the 48-bit prefix of an OpenCode ID's 26-character body. */
    private fun parsedValue(id: String): Long? {
        val underscore = id.indexOf('_')
        if (underscore < 0) return null
        val bodyStart = underscore + 1
        var bodyEnd = bodyStart
        while (bodyEnd < id.length && id[bodyEnd] != '-') bodyEnd++
        if (bodyEnd - bodyStart != 26) return null
        var value = 0L
        for (i in bodyStart until bodyStart + 12) {
            val digit = when (val c = id[i]) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                else -> return null
            }
            value = (value shl 4) or digit.toLong()
        }
        for (i in bodyStart + 12 until bodyEnd) {
            val c = id[i]
            if (c !in '0'..'9' && c !in 'A'..'Z' && c !in 'a'..'z') return null
        }
        return value
    }

    /**
     * iOS `NewMessagesMark.Tally`/`recordSpan` (DEBUG): counts the rows the mark's span reads (a turn
     * rebuilt, the own-message check) while a test holds a tally, so the test can prove a streamed
     * row doesn't read the transcript again.
     */
    class Tally {
        @Volatile var count: Int = 0
            private set

        @Synchronized fun add(rows: Int) {
            count += rows
        }
    }

    private val tally = ThreadLocal<Tally?>()

    /** Runs [block] with [value] counting the spans read on this thread (iOS `$tally.withValue`). */
    fun <T> withTally(value: Tally, block: () -> T): T {
        val previous = tally.get()
        tally.set(value)
        try {
            return block()
        } finally {
            tally.set(previous)
        }
    }

    fun recordSpan(rows: Int) {
        tally.get()?.add(rows)
    }
}

/** Rows kept above the New divider (iOS `SessionFeature.State.newMessagesContext`). */
val SessionFeature.State.Companion.newMessagesContext: Int get() = NewMessagesMark.NEW_MESSAGES_CONTEXT

/** Whether the New Messages Divider experiment is on. Off, what arrived while away comes in at the end with no divider, as in a chat app. */
val SessionFeature.State.Companion.showsNewMessagesDivider: Boolean
    get() = dependency(ExperimentalFeatures)(ExperimentalFeature.newMessagesDivider)

/** What was on screen as the reader leaves, kept until the read that follows their return settles what's new. */
fun SessionFeature.State.noteLeaving(): SessionFeature.State {
    if (isDraft) return this
    val unreadMarkID = newMessages?.itemID
    val earlierBaseline = newMessagesEarlierBaseline
    // What it marked has been in front of the reader: the divider goes while they're away, so the
    // next one arrives fresh rather than moving.
    val s = copy(newMessages = null, newMessagesFitThrough = null, newMessagesEarlierBaseline = null)
    if (s.newMessagesBaseline != null) return s
    val unreadIndex = unreadMarkID?.let { id -> s.items.indexOfFirst { it.id == id } } ?: -1
    return when {
        earlierBaseline != null -> s.copy(
            newMessagesBaseline = earlierBaseline.seen,
            newMessagesBaselineTimestamp = earlierBaseline.viewedMilliseconds,
            newMessagesUnread = earlierBaseline.unread,
            newMessagesWatched = earlierBaseline.watched,
        )
        unreadIndex >= 0 -> s.copy(
            newMessagesBaseline = s.items.subList(0, unreadIndex).mapTo(HashSet()) { it.id },
            newMessagesBaselineTimestamp = null,
            newMessagesUnread = unreadIndex == 0,
            newMessagesWatched = emptySet(),
        )
        else -> s.copy(
            newMessagesBaseline = s.items.mapTo(HashSet()) { it.id },
            newMessagesBaselineTimestamp = null,
            newMessagesUnread = false,
            newMessagesWatched = emptySet(),
        )
    }
}

/** Opening a session: what the reader saw last time they were here, or, when this run hasn't shown it, the saved copy it opens with. */
fun SessionFeature.State.noteOpening(
    seen: Set<String>?,
    viewedMilliseconds: Long? = null,
    unread: Boolean = false,
): SessionFeature.State {
    if (isDraft) return this
    val baseline: Set<String>? = when {
        seen != null && seen.isNotEmpty() -> seen
        unread || viewedMilliseconds != null -> emptySet()
        seen != null -> seen
        else -> if (items.isEmpty()) null else items.mapTo(HashSet()) { it.id }
    }
    return copy(
        newMessagesBaseline = baseline,
        newMessagesBaselineTimestamp = viewedMilliseconds,
        newMessagesUnread = unread,
        newMessagesEarlierBaseline = null,
        newMessagesWatched = emptySet(),
    ).updateNewMessagesMark(settles = false)
}

// `noteOwnSend()` lives in `RunErrorState.kt` (group SEND).

/**
 * A mark that fit on screen showed at the bottom without the divider: in front of the reader, its
 * rows are seen, so only what comes after them can make a divider. (While away the list keeps
 * measuring the whole span: rows that each fit can add up to a backlog that doesn't.)
 */
fun SessionFeature.State.absorbFittingNewMessages(): SessionFeature.State {
    val through = newMessagesFitThrough ?: return this
    val s = copy(newMessagesFitThrough = null)
    val baseline = s.newMessagesBaseline ?: return s
    val end = s.items.indexOfFirst { it.id == through }
    if (end < 0) return s
    return s.copy(
        newMessagesBaseline = baseline + s.items.subList(0, end + 1).map { it.id },
        newMessagesBaselineTimestamp = null,
        newMessagesUnread = false,
    )
}

/**
 * Places the divider above the first row the reader hasn't seen. The read after their return
 * [settles] it: with nothing new the divider goes, and what arrives from then on arrives in front
 * of them. The list shows the divider only with the experiment on and when what it marks doesn't
 * fit on screen (`newMessagesFit`). With it off the mark is still placed, without a divider: it's
 * what lets a following reader glide to what arrived instead of the rows landing in one frame.
 * Only rows that [keptShapes] (grew or were added, [NewMessagesFold.keepsShapes]) may reuse a fold
 * found before.
 */
fun SessionFeature.State.updateNewMessagesMark(settles: Boolean, keptShapes: Boolean = false): SessionFeature.State {
    var s = if (keptShapes) this else copy(newMessagesFolded = null)
    val seen = s.newMessagesBaseline
    val watched = s.newMessagesWatched
    val viewedMilliseconds = s.newMessagesBaselineTimestamp
    val unread = s.newMessagesUnread
    fun settled(state: SessionFeature.State): SessionFeature.State =
        if (!settles) {
            state
        } else {
            state.copy(
                newMessagesBaseline = null,
                newMessagesBaselineTimestamp = null,
                newMessagesUnread = false,
                newMessagesWatched = emptySet(),
            )
        }
    if (seen == null) return settled(s)
    val itemID = NewMessagesUnseen.firstUnseen(s.items, seen, watched, viewedMilliseconds, unread)
    val index = itemID?.let { id -> s.items.indexOfLast { it.id == id } } ?: -1
    if (itemID != null && index >= 0) {
        val items = s.items
        val lastItemID = items.lastOrNull()?.id
        val moves = s.newMessages?.itemID != itemID || s.newMessages?.lastItemID != lastItemID
        // Everything new joined a group the reader already had on screen: no row to put the divider
        // above, so nothing is marked. A mark kept anyway would wake on the next row to stream in
        // front of them. A mark already on this row found its entry; rows arriving after it can't
        // fold it into an earlier one.
        val span = NewMessagesFold(itemID = itemID, lastItemID = lastItemID, count = items.size)
        if (moves && s.newMessages?.itemID != itemID && (s.newMessagesFolded == span || !s.startsAnEntry(index))) {
            return settled(s.copy(newMessages = null, newMessagesFolded = span))
        }
        // The divider and what's below it show, however many arrived, with the usual window's worth
        // above it: landing at the divider mustn't put the reader at the top, paging in more history
        // under them. Short of every loaded row while older history may still come: a window that
        // reaches them all asks for it. The context comes with the first frame only: rows added
        // above the reader once it's drawn would page in under them.
        val needed = items.size - index + 1
        val reach = items.size - index + SessionFeature.State.newMessagesContext
        val context = if (s.hasRenderedFirstContentFrame) {
            needed
        } else {
            minOf(if (s.earlierHistory == EarlierHistory.Exhausted) items.size else items.size - 1, reach)
        }
        val limit = maxOf(s.visibleLimit, needed, context)
        if (limit != s.visibleLimit) s = s.copy(visibleLimit = limit)
        if (moves) {
            // Measured again as a whole: an earlier fit says nothing about it.
            val generation = s.newMessagesGeneration + 1
            s = s.copy(
                newMessagesFitThrough = null,
                newMessagesGeneration = generation,
                newMessages = NewMessagesMark(
                    itemID = itemID,
                    lastItemID = lastItemID,
                    generation = generation,
                    showsDivider = SessionFeature.State.showsNewMessagesDivider,
                ),
            )
        }
        if (settles) {
            val mayHaveEarlierUnseen = SessionFeature.State.showsNewMessagesDivider &&
                index == 0 &&
                s.earlierHistory != EarlierHistory.Exhausted &&
                (s.earlierHistory != EarlierHistory.UnknownEdge || items.size >= SessionFeature.initialFetchLimit)
            s = s.copy(
                newMessagesEarlierBaseline = if (mayHaveEarlierUnseen) {
                    NewMessagesEarlierBaseline(seen = seen, watched = watched, viewedMilliseconds = viewedMilliseconds, unread = unread)
                } else {
                    null
                },
            )
        }
    } else if (settles) {
        s = s.copy(newMessages = null, newMessagesEarlierBaseline = null)
    }
    return settled(s)
}

/** Recomputes a settled mark when an earlier history page prepends rows above a tail window that had overflowed the seen boundary. */
fun SessionFeature.State.recomputeNewMessagesAfterEarlierHistory(): SessionFeature.State {
    val earlier = newMessagesEarlierBaseline ?: return this
    return copy(
        newMessagesBaseline = earlier.seen,
        newMessagesWatched = earlier.watched,
        newMessagesBaselineTimestamp = earlier.viewedMilliseconds,
        newMessagesUnread = earlier.unread,
    ).updateNewMessagesMark(settles = true)
}

/** Whether some entry starts at or after the item at [index]: an item folded into a group that started earlier has no row of its own. */
private fun SessionFeature.State.startsAnEntry(index: Int): Boolean {
    // Groups never cross the reader's own message: build from the turn's.
    val turn = items.subList(0, index).indexOfLast { TranscriptEntry.promptID(it) != null }.coerceAtLeast(0)
    NewMessagesUnseen.recordSpan(items.size - turn)
    val ids = items.subList(index, items.size).mapTo(HashSet()) { it.id }
    return TranscriptEntry.build(items.subList(turn, items.size)).any { entry -> entry.itemIDs.firstOrNull()?.let { it in ids } ?: false }
}

/** Rows that streamed in while the reader watched, before the read after their return settled: seen, so the read never marks them. */
fun SessionFeature.State.noteWatched(previousIDs: Set<String>): SessionFeature.State {
    if (newMessagesBaseline == null) return this
    val added = items.filter { it.id !in previousIDs }.map { it.id }
    if (added.all { it in newMessagesWatched }) return this
    return copy(newMessagesWatched = newMessagesWatched + added)
}

fun SessionFeature.State.noteWatched(itemID: String): SessionFeature.State {
    if (newMessagesBaseline == null || itemID in newMessagesWatched) return this
    return copy(newMessagesWatched = newMessagesWatched + itemID)
}

/** The reader's own message (from another client, or spoken on a call) came in under the divider: like a send here, it reads what's above. */
fun SessionFeature.State.clearNewMessagesAfterOwnPrompt(): SessionFeature.State {
    val mark = newMessages ?: return this
    val start = items.indexOfFirst { it.id == mark.itemID }
    if (start < 0) return this
    val suffix = items.subList(start, items.size)
    NewMessagesUnseen.recordSpan(suffix.size)
    val isOwn = NewMessagesUnseen.ownPrompts(suffix)
    if (suffix.none(isOwn)) return this
    return noteOwnSend()
}

/**
 * Something came over the transcript (a subagent, its browser, Settings) or went: covered is
 * away, uncovered is back, with what the stream brought meanwhile already read in.
 */
fun SessionFeature.State.setTranscriptCovered(covered: Boolean): SessionFeature.State {
    if (covered == isTranscriptCovered) return this
    val s = copy(isTranscriptCovered = covered)
    if (!s.isVisible) return s
    return if (covered) {
        if (s.hasRenderedFirstContentFrame && s.isSceneActive) s.noteLeaving() else s
    } else {
        s.absorbFittingNewMessages().updateNewMessagesMark(settles = true)
    }
}

/** In front of the reader: on screen in an active foreground scene, with nothing over the transcript. */
val SessionFeature.State.watchesTranscript: Boolean
    get() = isVisible && isSceneActive && !isBackgrounded && !isTranscriptCovered
