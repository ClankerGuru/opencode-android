package ai.opencode.feature.drawer

import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.feature.drawer.bridge.DrawerBadge
import ai.opencode.feature.drawer.bridge.SessionDetail
import ai.opencode.feature.drawer.bridge.SessionSwitchSnapshot
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.SessionActivity
import ai.opencode.feature.drawer.HomeFeature.State.Indicator
import ai.opencode.feature.drawer.HomeFeature.State.StatusSnapshot
import ai.opencode.feature.drawer.HomeFeature.State.Viewing
import java.time.Instant

/*
 * Port of iOS `HomeFeature+DrawerState.swift`: the drawer's derived lists, read state, pins and
 * mutes, Catch Up's queue upkeep, and what a deleted session leaves behind. iOS mutates `inout
 * State`; these return the next state. Shared keys are written through (only when changed) along
 * with their mirrors in [HomeFeature.State].
 */

// region Shared-key write-through

/** `$pinnedSessionKeys.withLock { … }`. */
internal fun HomeFeature.State.updatingPinnedSessionKeys(transform: (List<String>) -> List<String>): HomeFeature.State {
    val next = transform(pinnedSessionKeys)
    if (next == pinnedSessionKeys) return this
    shared(SharedKeys.pinnedSessionKeys).set(next)
    return copy(pinnedSessionKeys = next)
}

/** `$lastViewedSessionIDs.withLock { … }`. */
internal fun HomeFeature.State.updatingLastViewedSessionIDs(transform: (Map<String, String>) -> Map<String, String>): HomeFeature.State {
    val next = transform(lastViewedSessionIDs)
    if (next == lastViewedSessionIDs) return this
    shared(SharedKeys.lastViewedSessionIDs).set(next)
    return copy(lastViewedSessionIDs = next)
}

/** `$sessionReadDates.withLock { … }`. */
internal fun HomeFeature.State.updatingSessionReadDates(transform: (Map<String, Instant>) -> Map<String, Instant>): HomeFeature.State {
    val next = transform(sessionReadDates)
    if (next == sessionReadDates) return this
    shared(SharedKeys.sessionReadDates).set(next)
    return copy(sessionReadDates = next)
}

/** `$manuallyUnreadSessionKeys.withLock { … }`. */
internal fun HomeFeature.State.updatingManuallyUnreadSessionKeys(transform: (Set<String>) -> Set<String>): HomeFeature.State {
    val next = transform(manuallyUnreadSessionKeys)
    if (next == manuallyUnreadSessionKeys) return this
    shared(SharedKeys.manuallyUnreadSessionKeys).set(next)
    return copy(manuallyUnreadSessionKeys = next)
}

/** `$mutedSessionKeys.withLock { … }`. */
internal fun HomeFeature.State.updatingMutedSessionKeys(transform: (Set<String>) -> Set<String>): HomeFeature.State {
    val next = transform(mutedSessionKeys)
    if (next == mutedSessionKeys) return this
    shared(SharedKeys.mutedSessionKeys).set(next)
    return copy(mutedSessionKeys = next)
}

/** `$initializedInboxServerIDs.withLock { … }`. */
internal fun HomeFeature.State.updatingInitializedInboxServerIDs(transform: (Set<String>) -> Set<String>): HomeFeature.State {
    val next = transform(initializedInboxServerIDs)
    if (next == initializedInboxServerIDs) return this
    shared(SharedKeys.initializedInboxServerIDs).set(next)
    return copy(initializedInboxServerIDs = next)
}

/** `$isSidebarShown.withLock { … }`. */
internal fun HomeFeature.State.settingSidebarShown(shown: Boolean): HomeFeature.State {
    if (shown == isSidebarShown) return this
    shared(SharedKeys.sidebarShown).set(shown)
    return copy(isSidebarShown = shown)
}

/** Replaces one listed session in place. */
internal inline fun HomeFeature.State.updatingSession(sessionID: String, transform: (OpenCodeSession) -> OpenCodeSession): HomeFeature.State {
    val index = sessions.indexOfFirst { it.id == sessionID }
    if (index < 0) return this
    val next = transform(sessions[index])
    if (next == sessions[index]) return this
    return copy(sessions = sessions.toMutableList().also { it[index] = next })
}

/** Replaces one remembered snapshot in place (`sessionSnapshots[id]?.… = …`). */
internal inline fun HomeFeature.State.updatingSnapshot(
    sessionID: String,
    transform: (SessionSwitchSnapshot) -> SessionSwitchSnapshot,
): HomeFeature.State {
    val snapshot = sessionSnapshots[sessionID] ?: return this
    val next = transform(snapshot)
    if (next == snapshot) return this
    return copy(sessionSnapshots = sessionSnapshots + (sessionID to next))
}

/** `sessionDetail?.… = …`. */
internal inline fun HomeFeature.State.updatingDetail(transform: (SessionDetail) -> SessionDetail): HomeFeature.State {
    val detail = sessionDetail ?: return this
    val next = transform(detail)
    return if (next == detail) this else copy(sessionDetail = next)
}

/** `subagentStack[id: id]?.… = …` for each pushed screen. */
internal inline fun HomeFeature.State.updatingSubagents(transform: (SessionDetail) -> SessionDetail): HomeFeature.State {
    if (subagentStack.isEmpty()) return this
    val next = subagentStack.map(transform)
    return if (next == subagentStack) this else copy(subagentStack = next)
}

/** `subagentStack[id: id]?.… = …`. */
internal inline fun HomeFeature.State.updatingSubagent(id: Int, transform: (SessionDetail) -> SessionDetail): HomeFeature.State {
    val index = subagentStack.indexOfFirst { it.navigationID == id }
    if (index < 0) return this
    val next = transform(subagentStack[index])
    if (next == subagentStack[index]) return this
    return copy(subagentStack = subagentStack.toMutableList().also { it[index] = next })
}

// endregion

/** Sessions that need you or are running first, then newest. */
fun HomeFeature.State.sessionsByPriority(): List<OpenCodeSession> {
    val busy = busySessionIDs
    fun isHot(session: OpenCodeSession) = session.id in attentionSessionIDs || session.id in busy
    return sessions.sortedWith { a, b ->
        val aHot = isHot(a)
        val bHot = isHot(b)
        if (aHot != bHot) (if (aHot) -1 else 1) else b.updatedAt.compareTo(a.updatedAt)
    }
}

val HomeFeature.State.visibleSessions: List<OpenCodeSession>
    get() = if (search.isEmpty()) sessions else sessions.filter { it.title.contains(search, ignoreCase = true) }

val HomeFeature.State.pinnedSessions: List<OpenCodeSession>
    get() {
        val keys = pinnedSessionKeys
        if (keys.isEmpty()) return emptyList()
        val prefix = server.persistenceID + "|"
        if (keys.none { it.startsWith(prefix) }) return emptyList()
        val indexed = visibleSessions.associateBy { prefix + it.id }
        return keys.mapNotNull { indexed[it] }
    }

val HomeFeature.State.recentSessions: List<OpenCodeSession>
    get() {
        val pinned = pinnedSessionKeys.toHashSet()
        val visible = visibleSessions
        val prefix = if (pinned.isEmpty()) "" else server.persistenceID + "|"
        val unpinned = if (pinned.isEmpty()) visible else visible.filter { (prefix + it.id) !in pinned }
        return unpinned.sortedByDescending { it.updatedAt }
    }

/** Waiting sessions first, then newest. */
val HomeFeature.State.catchUpSessions: List<OpenCodeSession> get() = catchUpSessions(statusSnapshot())

private fun HomeFeature.State.catchUpSessions(status: StatusSnapshot): List<OpenCodeSession> {
    val attention = status.attention
    return sessions.filter { status.needsCatchUp(it) }.sortedWith { a, b ->
        val aWaiting = a.id in attention
        val bWaiting = b.id in attention
        if (aWaiting != bWaiting) (if (aWaiting) -1 else 1) else b.updatedAt.compareTo(a.updatedAt)
    }
}

/** Back to the session Catch Up was opened from, with the composer it set aside (or a draft). */
fun HomeFeature.State.returningFromCatchUp(): HomeFeature.State {
    val (exiting, catchUpState) = catchUpState.consumeExitingStack()
    var s = copy(catchUpState = catchUpState)
    val stack = exiting ?: s.catchUp ?: return s
    s = s.withCatchUp(null)
    s = s.parkingDraft(stack.returnComposer as? ai.opencode.feature.drawer.bridge.SessionDraftState, stack.composerTargetSessionID)
    val session = stack.returnSessionID?.let { id -> s.sessions.firstOrNull { it.id == id } }
    s = if (session != null) {
        s.openingDetail(session, cachedItems = s.cachedTranscriptItems(session), reason = "you left Catch Up")
    } else {
        s.openingDraft(reason = "you left Catch Up")
    }
    return s.absorbingSharedDrafts()
}

/** Leaving Catch Up any way but Exit: the composer it set aside goes back to its own session. */
fun HomeFeature.State.leavingCatchUp(): HomeFeature.State {
    val stack = catchUp ?: return this
    return withCatchUp(null).parkingDraft(
        stack.returnComposer as? ai.opencode.feature.drawer.bridge.SessionDraftState,
        stack.composerTargetSessionID,
    )
}

/**
 * Opens the stack's top card without marking it read: reading is the reader's swipe, not the card
 * appearing. Returns whether the session changed.
 */
fun HomeFeature.State.openingCatchUpCard(): Pair<HomeFeature.State, Boolean> {
    val id = catchUp?.queue?.firstOrNull() ?: return this to false
    if (sessionDetail?.familyRootID == id && sessionDetail.readsOnView == false) return this to false
    val session = sessions.firstOrNull { it.id == id } ?: return this to false
    val changedSession = sessionDetail?.session?.id != id
    val (next, detail) = makeDetail(session, cachedItems = cachedTranscriptItems(session), reason = "you opened it from Catch Up")
    return next.copy(sessionDetail = detail.copy(readsOnView = false)) to changedSession
}

/** Follows reads, answers, and new activity from any device. The open card stays until the reader moves it along. */
fun HomeFeature.State.reconcilingCatchUp(): HomeFeature.State {
    val stack = catchUp ?: return this
    val status = statusSnapshot()
    val attention = status.attention
    val byID = LinkedHashMap<String, OpenCodeSession>()
    for (session in sessions) byID.putIfAbsent(session.id, session)
    val synced = stack.syncQueue(
        sessionsByID = byID,
        candidates = catchUpSessions(status),
        needsCatchUp = { status.needsCatchUp(it) },
        needsAttention = { it in attention },
    )
    if (synced == stack) return this
    return withCatchUp(synced).openingCatchUpCard().first
}

fun HomeFeature.State.markingUnreadLocally(session: OpenCodeSession): HomeFeature.State {
    var s = withMarkAllReceipts(markAllReceipts - session.id)
        .copy(locallyViewedMilliseconds = locallyViewedMilliseconds - session.id)
    val key = sessionKey(session.id)
    s = s.updatingSessionReadDates { it + (key to session.updatedAt.minusSeconds(1)) }
    if (session.idleMilliseconds != null) s = s.updatingManuallyUnreadSessionKeys { it + key }
    return s
}

fun HomeFeature.State.readBackup(session: OpenCodeSession, idle: Long): CatchUpFeature.ReadBackup {
    val key = sessionKey(session.id)
    return CatchUpFeature.ReadBackup(
        idle = idle,
        viewedMilliseconds = sessions.firstOrNull { it.id == session.id }?.viewedMilliseconds,
        locallyViewedMilliseconds = locallyViewedMilliseconds[session.id],
        readDate = sessionReadDates[key],
        wasManuallyUnread = key in manuallyUnreadSessionKeys,
    )
}

/** Undoes `markReadLocally` for a receipt the server refused. */
fun HomeFeature.State.restoringRead(sessionID: String, backup: CatchUpFeature.ReadBackup): HomeFeature.State {
    val key = sessionKey(sessionID)
    var s = updatingSession(sessionID) {
        if (it.viewedMilliseconds == maxOf(backup.viewedMilliseconds ?: 0, backup.idle)) {
            it.copy(viewedMilliseconds = backup.viewedMilliseconds)
        } else {
            it
        }
    }
    s = s.copy(
        locallyViewedMilliseconds = backup.locallyViewedMilliseconds?.let { s.locallyViewedMilliseconds + (sessionID to it) }
            ?: (s.locallyViewedMilliseconds - sessionID),
    )
    s = s.updatingSessionReadDates { dates -> backup.readDate?.let { dates + (key to it) } ?: (dates - key) }
    if (backup.wasManuallyUnread) s = s.updatingManuallyUnreadSessionKeys { it + key }
    return s
}

fun HomeFeature.State.notingViewAcknowledged(sessionID: String, idle: Long): HomeFeature.State {
    var s = updatingSession(sessionID) { it.copy(viewedMilliseconds = maxOf(it.viewedMilliseconds ?: 0, idle)) }
    val key = sessionKey(sessionID)
    if (key in s.manuallyUnreadSessionKeys) s = s.updatingManuallyUnreadSessionKeys { it - key }
    return s
}

fun HomeFeature.State.sessionKey(id: String): String = "${server.persistenceID}|$id"
fun HomeFeature.State.isPinned(id: String): Boolean = sessionKey(id) in pinnedSessionKeys
fun HomeFeature.State.isMuted(id: String): Boolean = sessionKey(id) in mutedSessionKeys
fun HomeFeature.State.isUnread(session: OpenCodeSession): Boolean {
    if (sessionKey(session.id) in manuallyUnreadSessionKeys) return true
    session.idleMilliseconds?.let { return it > (session.viewedMilliseconds ?: -1) }
    return sessionReadDates[sessionKey(session.id)]?.let { it < session.updatedAt } ?: true
}

/**
 * Moves a session to the top of Recents the moment the reader sends, rather than when the next list
 * refresh carries the server's time. The reader's own send must not make the chat look unread.
 */
fun HomeFeature.State.notingLocalActivity(sessionID: String, at: Instant): HomeFeature.State {
    val session = sessions.firstOrNull { it.id == sessionID } ?: return this
    if (session.updatedAt >= at) return this
    val serverUpdatedAt = localActivity[sessionID]?.serverUpdatedAt ?: session.updatedAt
    return copy(localActivity = localActivity + (sessionID to HomeFeature.State.LocalActivity(serverUpdatedAt, at)))
        .updatingSession(sessionID) { it.copy(updatedAt = at) }
        .updatingSessionReadDates { it + (sessionKey(sessionID) to at) }
}

fun HomeFeature.State.markingReadLocally(session: OpenCodeSession): HomeFeature.State {
    val key = sessionKey(session.id)
    var s = this
    if (s.markAllReceipts[session.id] != null) s = s.withMarkAllReceipts(s.markAllReceipts - session.id)
    val idle = session.idleMilliseconds
    if (idle != null && s.sessions.any { it.id == session.id }) {
        s = s.updatingSession(session.id) { it.copy(viewedMilliseconds = maxOf(it.viewedMilliseconds ?: 0, idle)) }
        val localViewed = maxOf(s.locallyViewedMilliseconds[session.id] ?: 0, idle)
        if (s.locallyViewedMilliseconds[session.id] != localViewed) {
            s = s.copy(locallyViewedMilliseconds = s.locallyViewedMilliseconds + (session.id to localViewed))
        }
    }
    if (s.sessionReadDates[key] != session.updatedAt) s = s.updatingSessionReadDates { it + (key to session.updatedAt) }
    if (key in s.manuallyUnreadSessionKeys) s = s.updatingManuallyUnreadSessionKeys { it - key }
    return s
}

/** Forgets a session the server has deleted: its row and every phone-local record keyed by it. */
fun HomeFeature.State.purgingSession(sessionID: String): HomeFeature.State {
    var s = copy(
        sessions = sessions.filterNot { it.id == sessionID },
        activeSessionIDs = activeSessionIDs - sessionID,
        activeSessionParents = activeSessionParents - sessionID,
        attentionSessionIDs = attentionSessionIDs - sessionID,
        runChangesDuringRefresh = runChangesDuringRefresh - sessionID,
        locallyViewedMilliseconds = locallyViewedMilliseconds - sessionID,
        localActivity = localActivity - sessionID,
        transcriptCache = transcriptCache - sessionID,
        refreshingTranscripts = refreshingTranscripts - sessionID,
        dirtyCachedTranscriptIDs = dirtyCachedTranscriptIDs - sessionID,
        transcriptReadAt = transcriptReadAt - sessionID,
        sessionSnapshots = (sessionSnapshots - sessionID).mapValues { (_, snapshot) ->
            // A deleted subagent's requests leave the cached family too.
            snapshot.copy(family = snapshot.family.filterNot { it.id == sessionID })
                .pruningRequests { owner, _ -> owner == sessionID }
        },
        sessionSnapshotOrder = sessionSnapshotOrder - sessionID,
        parkedDrafts = parkedDrafts - sessionID,
    )
    val serverID = server.persistenceID
    ServerSharedKeys.cachedTranscriptSnapshots().update { it.removeEntry(serverID, sessionID) }
    val key = sessionKey(sessionID)
    s = s.updatingPinnedSessionKeys { keys -> keys.filterNot { it == key } }
        .updatingSessionReadDates { it - key }
        .updatingManuallyUnreadSessionKeys { it - key }
        .updatingMutedSessionKeys { it - key }
    if (s.lastViewedSessionIDs[serverID] == sessionID) s = s.updatingLastViewedSessionIDs { it - serverID }
    return s
}

fun HomeFeature.State.statusSnapshot(busy: Set<String>? = null): StatusSnapshot =
    // Reconnecting keeps the last-known state on screen while it is fresh (live within
    // `lastKnownActivityLifetime`), so a routine reconnect or a quick relaunch doesn't blank every
    // row. Older state, or an outage, has nothing trustworthy to show.
    StatusSnapshot(
        serverID = server.persistenceID,
        showsActivity = isConnected ||
            (connectionStatus == HomeConnectionFeature.State.ConnectionStatus.reconnecting && lastKnownActivityDeadline != null),
        busy = busy ?: busySessionIDs,
        attention = attentionSessionIDs,
        mutedKeys = mutedSessionKeys,
        manuallyUnreadKeys = manuallyUnreadSessionKeys,
        readDates = sessionReadDates,
    )

fun HomeFeature.State.indicator(session: OpenCodeSession): Indicator = statusSnapshot().indicator(session)

val HomeFeature.State.sessionIndicators: Map<String, Indicator>
    get() {
        val status = statusSnapshot()
        return sessions.associate { it.id to status.indicator(it) }
    }

/** Whether a notice may show now: the app is in front on a session, and neither the drawer, Settings, nor Catch Up is up. */
val HomeFeature.State.canShowInAppNotice: Boolean
    get() = sessionDetail?.isVisible == true && !isDrawerOpen && !isSettingsShown && catchUp == null

/** The open session and its family's root. */
val HomeFeature.State.openSessionIDs: Set<String>
    get() = setOfNotNull(sessionDetail?.session?.id, sessionDetail?.familyRootID)

val HomeFeature.State.viewing: Viewing
    get() {
        val detail = sessionDetail ?: return Viewing()
        if (!detail.isVisible) return Viewing()
        return Viewing(detail.session?.id, detail.familyRootID)
    }

/** The sessions whose delivered notifications go when they're on screen. */
val HomeFeature.State.viewedNotificationThreads: Set<String> get() = viewing.threads

/** The drawer button's dot: whether any session besides the open one needs input or is unread. Muted sessions never light it. */
fun HomeFeature.State.drawerBadge(excluding: String?): DrawerBadge? {
    if (!(isConnected ||
            (connectionStatus == HomeConnectionFeature.State.ConnectionStatus.reconnecting && lastKnownActivityDeadline != null))
    ) {
        return null
    }
    val attention = attentionSessionIDs
    val unreadCandidates = mutableListOf<String>()
    for (session in sessions) {
        if (session.id == excluding || isMuted(session.id)) continue
        if (session.id in attention) return DrawerBadge.Attention
        if (isUnread(session)) unreadCandidates += session.id
    }
    if (unreadCandidates.isEmpty()) return null
    val busy = busySessionIDs
    return if (unreadCandidates.any { it !in busy }) DrawerBadge.Unread else null
}

/**
 * Records local activity on the family root when [detail] accepted a send (draft cleared and
 * awaiting reply); returns the state and the root ID.
 */
fun HomeFeature.State.notingScreenSendIfAccepted(detail: SessionDetail?, at: () -> Instant): Pair<HomeFeature.State, String?> {
    if (detail == null || !detail.draft.isEmpty) return this to null
    val rootID = sessionDetail?.familyRootID ?: sessionDetail?.session?.id ?: detail.familyRootID ?: detail.session?.id
        ?: return this to null
    return notingLocalActivity(rootID, at()) to rootID
}

fun HomeFeature.State.invalidatingSnapshotFamilyReads(
    matching: (SessionSwitchSnapshot) -> Boolean = { true },
): HomeFeature.State {
    var changed = false
    val next = sessionSnapshots.mapValues { (_, snapshot) ->
        if (snapshot.familyReadAt != null && matching(snapshot)) {
            changed = true
            snapshot.copy(familyReadAt = null)
        } else {
            snapshot
        }
    }
    return if (changed) copy(sessionSnapshots = next) else this
}

/** Merges the ancestry above [sessionIDs] into `activeSessionParents`. */
fun HomeFeature.State.recordingActiveAncestry(sessionIDs: Set<String>, parents: Map<String, String>? = null): HomeFeature.State {
    if (sessionIDs.isEmpty()) return this
    val links = parents ?: knownParents
    var next: MutableMap<String, String>? = null
    for ((childID, parentID) in SessionActivity.ancestry(sessionIDs, links)) {
        if (activeSessionParents[childID] != parentID) {
            if (next == null) next = activeSessionParents.toMutableMap()
            next[childID] = parentID
        }
    }
    return next?.let { copy(activeSessionParents = it) } ?: this
}

/**
 * iOS `syncBusyAncestryFromFamily(deletedSessionID:)`: the open family's links (read by the screen)
 * join the parents kept for running sessions, so a root stays lit after a switch away.
 */
fun HomeFeature.State.syncingBusyAncestryFromFamily(deletedSessionID: String? = null): HomeFeature.State {
    var s = this
    if (deletedSessionID != null && deletedSessionID in s.activeSessionParents) {
        s = s.copy(activeSessionParents = s.activeSessionParents - deletedSessionID)
    }
    if (s.activeSessionIDs.isEmpty()) return s
    return s.recordingActiveAncestry(s.activeSessionIDs)
}
