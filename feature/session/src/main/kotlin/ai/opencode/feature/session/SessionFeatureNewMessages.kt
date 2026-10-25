package ai.opencode.feature.session

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.feature.transcript.list.NewMessagesMark
import java.time.Instant

// The `SessionFeature.State` members of iOS `Transcript/NewMessagesMark.swift`. They extend the
// session's state, so they live in this module; `NewMessagesMark` itself (and the list's
// `NewMessagesPlacement`) belong to `:feature:transcript`.

/** Whether the New Messages Divider experiment is on. Off, what arrived while away comes in at the end with no divider, as in a chat app. */
val SessionFeature.State.Companion.showsNewMessagesDivider: Boolean
    get() = dependency(ExperimentalFeatures)(ExperimentalFeature.newMessagesDivider)

/** Whether the Since You Left experiment is on. */
val SessionFeature.State.Companion.showsSinceYouLeft: Boolean
    get() = dependency(ExperimentalFeatures)(ExperimentalFeature.sinceYouLeft)

/** What was on screen as the reader leaves, kept until the read that follows their return settles what's new. */
fun SessionFeature.State.noteLeaving(leftAt: Instant? = null): SessionFeature.State {
    if (isDraft) return this
    // What it marked has been in front of the reader: the divider goes while they're away, so the
    // next one arrives fresh rather than moving.
    var s = copy(newMessages = null, newMessagesFitThrough = null, sinceYouLeft = null, sinceYouLeftDismissed = false)
    val tracksSinceYouLeft = SessionFeature.State.showsSinceYouLeft || leftAt != null
    val stampedLeaveDate = leftAt ?: SessionFeature.State.defaultLeaveDate()
    val baseline = s.newMessagesBaseline
    if (baseline != null) {
        if (tracksSinceYouLeft) {
            if (s.sinceYouLeftSeen == null) s = s.copy(sinceYouLeftSeen = baseline)
            if (s.sinceYouLeftAt == null) s = s.copy(sinceYouLeftAt = stampedLeaveDate)
        }
        return s
    }
    val seen = s.items.mapTo(HashSet()) { it.id }
    s = s.copy(newMessagesBaseline = seen)
    if (tracksSinceYouLeft) s = s.copy(sinceYouLeftSeen = seen, sinceYouLeftAt = stampedLeaveDate)
    return s
}

/** Opening a session: what the reader saw last time they were here, or, when this run hasn't shown it, the saved copy it opens with. */
fun SessionFeature.State.noteOpening(seen: Set<String>?, leftAt: Instant? = null): SessionFeature.State {
    if (isDraft) return this
    val baseline = seen ?: if (items.isEmpty()) null else items.mapTo(HashSet()) { it.id }
    var s = copy(newMessagesBaseline = baseline)
    s = if (SessionFeature.State.showsSinceYouLeft || leftAt != null) {
        s.copy(sinceYouLeftSeen = baseline, sinceYouLeftAt = leftAt)
    } else {
        s.copy(sinceYouLeftSeen = null, sinceYouLeftAt = null)
    }
    return s.copy(sinceYouLeft = null, sinceYouLeftDismissed = false).updateNewMessagesMark(settles = false)
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
    return s.copy(newMessagesBaseline = baseline + s.items.subList(0, end + 1).map { it.id })
}

/**
 * Places the divider above the first row the reader hasn't seen. The read after their return
 * [settles] it: with nothing new the divider goes, and what arrives from then on arrives in front
 * of them. The list shows the divider only with the experiment on and when what it marks doesn't
 * fit on screen (`newMessagesFit`).
 */
fun SessionFeature.State.updateNewMessagesMark(settles: Boolean): SessionFeature.State {
    val seen = newMessagesBaseline ?: return this
    var s = this
    val itemID = NewMessagesMark.firstUnseen(items, seen)
    if (itemID != null) {
        // The divider and what's below it show, however many arrived, with the usual window's
        // worth above it: landing at the divider mustn't put the reader at the top, paging in more
        // history under them. Short of every loaded row: a window that reaches them all asks for
        // older history. The context comes with the first frame only: rows added above the reader
        // once it's drawn would page in under them.
        val index = items.indexOfFirst { it.id == itemID }
        if (index >= 0) {
            val needed = items.size - index + 1
            val context = if (hasRenderedFirstContentFrame) needed else minOf(items.size - 1, items.size - index + NewMessagesMark.NEW_MESSAGES_CONTEXT)
            val limit = maxOf(visibleLimit, needed, context)
            if (limit != visibleLimit) s = s.copy(visibleLimit = limit)
        }
        val lastItemID = items.lastOrNull()?.id
        if (s.newMessages?.itemID != itemID || s.newMessages?.lastItemID != lastItemID) {
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
    } else if (settles) {
        s = s.copy(newMessages = null)
    }
    if (settles) s = s.copy(newMessagesBaseline = null)
    return s
}

/** iOS `SessionFeature.State.defaultLeaveDate()`: now, when Since You Left is on (it needs to know when the reader left). */
fun SessionFeature.State.Companion.defaultLeaveDate(): Instant? {
    if (!dependency(ExperimentalFeatures)(ExperimentalFeature.sinceYouLeft)) return null
    val now = dependency(DateClient).now()
    return if (now == Instant.EPOCH) null else now
}
