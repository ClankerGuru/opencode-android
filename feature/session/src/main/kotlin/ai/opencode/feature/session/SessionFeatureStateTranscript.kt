package ai.opencode.feature.session

import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.server.model.OpenCodeTranscriptItem

// Port of iOS `SessionFeature+StateTranscript.swift` (split out of `SessionFeature+State.swift`
// in 67a3fb61): the transcript window, what caches keep, and the loading and empty states.

/** The transcript as the server confirmed it, for caches: no message still in flight or failed. */
val State.cacheableItems: List<OpenCodeTranscriptItem> get() = items(optimisticPromptIDs + failedSends.ids)

/** Without failed sends' bubbles, which `restoreFailedSends` brings back. */
val State.itemsWithoutFailedSends: List<OpenCodeTranscriptItem> get() = items(failedSends.ids.toSet())

/** The first loaded row the transcript shows. */
val State.windowStart: Int
    get() {
        val latestStart = maxOf(0, items.size - visibleLimit)
        val start = minOf(latestStart, browsingStartIndex ?: latestStart)
        return TranscriptEntry.entryStart(start, items)
    }

val State.visibleItems: List<OpenCodeTranscriptItem>
    get() = hidingUndone(items.subList(minOf(windowStart, items.size), items.size))

val State.firstVisibleItemID: String?
    get() {
        if (session?.revertMessageID != null) return visibleItems.firstOrNull()?.id
        val start = windowStart
        return items.getOrNull(start)?.id
    }

/** Once drawn, keeps the window starting at the row it started at before an update (OC-375/386). */
fun State.retainRenderedWindowStart(previousFirstID: String?): State {
    if (!hasRenderedFirstContentFrame || previousFirstID == null) return this
    val index = if (items.firstOrNull()?.id == previousFirstID) {
        0
    } else {
        items.indexOfFirst { it.id == previousFirstID }.takeIf { it >= 0 }
            ?: items.indexOfFirst {
                val messageID = SessionFeature.messageID(it)
                messageID == previousFirstID || previousFirstID.startsWith("$messageID-")
            }.takeIf { it >= 0 }
            ?: return this
    }
    // A row removed below it must not pull an earlier one into view: then exactly the rows from it on.
    val nextLimit = if (windowStart < index) items.size - index else maxOf(visibleLimit, items.size - index)
    return if (visibleLimit != nextLimit) copy(visibleLimit = nextLimit) else this
}

fun State.pinBrowsingWindowIfNeeded(): State {
    if (browsingWindowStartID == null) return this
    val start = windowStart
    val nextID = items.getOrNull(start)?.id ?: firstVisibleItemID
    return if (browsingWindowStartID != nextID) copy(browsingWindowStartID = nextID) else this
}

val State.hasEarlierItems: Boolean
    get() = items.size > visibleLimit ||
        // A restored cache can be browsed immediately, but do not start the cursor chain from its stale edge.
        (earlierHistory != State.EarlierHistory.Exhausted && !isLoading &&
            ((!hasRenderedFirstContentFrame &&
                (earlierHistory != State.EarlierHistory.UnknownEdge || items.isEmpty() ||
                    (refresh == null && items.size >= SessionFeature.initialFetchLimit))) ||
                initialTranscriptIsFresh) &&
            !(items.isEmpty() && initialTranscriptIsFresh && earlierHistory == State.EarlierHistory.UnknownEdge))

/** Whether a transcript request is in flight or the first load has not yet been attempted. */
val State.isTranscriptLoading: Boolean
    get() = isLoading || isLoadingEarlier ||
        (!initialTranscriptIsFresh &&
            (refresh != null || initialTranscriptRequestIsExternal || (!isDraft && nextRefreshID == 0 && loadError == null)) &&
            items.isEmpty())

/** Whether the empty-session placeholder may be shown. */
val State.showsEmptyState: Boolean
    get() = !isDraft && initialTranscriptIsFresh && items.isEmpty() && queuedInputs.isEmpty() &&
        !isRunning && !isTranscriptLoading && !hasEarlierItems && earlierHistoryError == null
