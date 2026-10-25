package ai.opencode.feature.drawer.bridge

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.map
import ai.opencode.feature.session.SessionFeature
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeTranscriptItem
import java.time.Instant

/*
 * Drawer-local stand-ins for iOS `SessionFeature` types that `:feature:session` hasn't ported yet.
 * Home (iOS `HomeFeature*.swift`) reads and edits a lot of the open session's state directly; until
 * `SessionFeature.State` carries those fields, [SessionDetail] holds them beside the stub
 * `SessionFeature.State` it embeds. Every type here names its iOS original.
 */

/** iOS `SessionFeature.initialFetchLimit` / `firstFrameFetchLimit`. TODO(merge): use SessionFeature's constants. */
object SessionFeatureLimits {
    const val initialFetchLimit = 30
    const val firstFrameFetchLimit = 12
}

/**
 * The session screen Home presents (`sessionDetail`) or pushes (`subagentStack`): the stub
 * [SessionFeature.State] plus the iOS `SessionFeature.State` fields Home coordinates.
 *
 * TODO(merge): replace with `SessionFeature.State` once `:feature:session` has these fields
 * (`session`, `navigationID`, `visit`, `familyRootID`, `familySessions`, `familyReadAt`,
 * `activeFamilySessionIDs`, `isVisible`, `readsOnView`, `draftProject`, `projects`,
 * `currentModel`, `pendingModel`, `draft`, cached items …); `feature` then goes away.
 */
data class SessionDetail(
    val feature: SessionFeature.State,
    val session: OpenCodeSession? = null,
    /** Identity of this screen (TCA `id`): kept by a live call across moves, negative for pushes. */
    val navigationID: Int,
    /** Which move made it (`detailGeneration`), counted even when [navigationID] is kept. */
    val visit: Int = navigationID,
    val familyRootID: String? = session?.let { it.parentID ?: it.id },
    val familySessions: List<OpenCodeSession> = listOfNotNull(session),
    val familyReadAt: Instant? = null,
    val activeFamilySessionIDs: Set<String> = emptySet(),
    val items: List<OpenCodeTranscriptItem>? = null,
    val familyTranscriptCache: Map<String, List<OpenCodeTranscriptItem>> = emptyMap(),
    val initialTranscriptRequestIsExternal: Boolean = false,
    val isLoading: Boolean = false,
    val isRunning: Boolean = false,
    val isExternallyActive: Boolean = isRunning,
    val isVisible: Boolean = false,
    val isSceneActive: Boolean = true,
    val isCovered: Boolean = false,
    /** Catch Up's cards don't read on view: the reader's swipe does. */
    val readsOnView: Boolean = true,
    val streamIsHealthy: Boolean = false,
    val callsFromRoot: Boolean = false,
    val draftProject: OpenCodeProject? = null,
    val projects: List<OpenCodeProject> = emptyList(),
    val currentModel: OpenCodeModelRef? = null,
    val pendingModel: OpenCodeModelRef? = null,
    val draft: SessionDraftState = SessionDraftState(),
    val seenItemIDs: Set<String>? = null,
    val leftAt: Instant? = null,
    val permissionRequests: List<OpenCodePermissionRequest> = emptyList(),
    val formRequests: List<OpenCodeFormRequest> = emptyList(),
    val hasLoadedPendingRequests: Boolean = false,
    val shells: List<OpenCodeShell> = emptyList(),
    val queued: List<OpenCodePendingInput> = emptyList(),
) {
    val isDraft: Boolean get() = session == null

    /** iOS `sessionComposer`: the composer this screen keeps for its session. */
    val sessionComposer: SessionDraftState get() = draft

    /** iOS `hasPromptContent`. */
    val hasPromptContent: Boolean get() = draft.text.isNotEmpty()

    /** iOS `cacheableItems`: the transcript window worth caching. */
    val cacheableItems: List<OpenCodeTranscriptItem> get() = items.orEmpty()

    /** iOS `isAwaitingReply` (TODO(merge): SessionFeature owns it). */
    val isAwaitingReply: Boolean get() = isRunning

    /** Requests waiting in this family (iOS `waitingSessionIDs`). */
    val waitingSessionIDs: Set<String>
        get() = (permissionRequests.map { it.sessionID } + formRequests.map { it.sessionID }).toSet()

    /**
     * iOS `transcriptResponse(sessionID:_:pageCursor:)` handled by the screen.
     * TODO(merge): send `.sessionDetail(.presented(.transcriptResponse))` instead.
     */
    fun applyingTranscript(result: Result<List<OpenCodeTranscriptItem>>): SessionDetail =
        copy(
            items = result.getOrNull() ?: items,
            isLoading = false,
            initialTranscriptRequestIsExternal = false,
        )

    /** iOS `markViewed(_:)`: the screen's own copy of the session reads as viewed. */
    fun markingViewed(viewed: OpenCodeSession): SessionDetail {
        val idle = viewed.idleMilliseconds ?: return this
        fun mark(s: OpenCodeSession) =
            if (s.id == viewed.id) s.copy(viewedMilliseconds = maxOf(s.viewedMilliseconds ?: 0, idle)) else s
        return copy(session = session?.let(::mark), familySessions = familySessions.map(::mark))
    }

    companion object {
        /** iOS `SessionFeature.State(server:session:cachedItems:…)`. */
        fun forSession(
            session: OpenCodeSession,
            navigationID: Int,
            visit: Int = navigationID,
            cachedItems: List<OpenCodeTranscriptItem>? = null,
            externalTranscript: Boolean = false,
            isRunning: Boolean = false,
        ) = SessionDetail(
            feature = SessionFeature.State(sessionID = session.id, title = session.title),
            session = session,
            navigationID = navigationID,
            visit = visit,
            items = cachedItems,
            initialTranscriptRequestIsExternal = externalTranscript,
            isLoading = cachedItems == null,
            isRunning = isRunning,
        )

        /** iOS `SessionFeature.State(server:draftProject:projects:inheritedModel:…)`. */
        fun draft(
            draftProject: OpenCodeProject?,
            projects: List<OpenCodeProject>,
            inheritedModel: OpenCodeModelRef?,
            navigationID: Int,
            visit: Int = navigationID,
        ) = SessionDetail(
            feature = SessionFeature.State(),
            session = null,
            navigationID = navigationID,
            visit = visit,
            draftProject = draftProject,
            projects = projects,
            currentModel = inheritedModel,
        )

        /** Runs the stub [SessionFeature] on [SessionDetail.feature]. */
        fun reducer(child: SessionFeature = SessionFeature()): Reducer<SessionDetail, SessionFeature.Action> =
            Reducer { detail, action ->
                val reduced = child.reduce(detail.feature, action)
                Reduced(detail.copy(feature = reduced.state), reduced.effect.map { it })
            }
    }
}

/** TODO(merge): replace with `SessionFeature.State.DraftState` (the composer's text, attachments …). */
data class SessionDraftState(val text: String = "") {
    val isEmpty: Boolean get() = text.isEmpty()
}

/**
 * Last-known chrome for a visited session (no effects, sheets or speculative reads), restored when
 * it opens again. TODO(merge): replace with `SessionFeature.SwitchSnapshot`.
 */
data class SessionSwitchSnapshot(
    val session: OpenCodeSession,
    val family: List<OpenCodeSession> = listOf(session),
    val familyReadAt: Instant? = null,
    val permissions: List<OpenCodePermissionRequest> = emptyList(),
    val forms: List<OpenCodeFormRequest> = emptyList(),
    val hasLoadedRequests: Boolean = false,
    val seenItemIDs: Set<String>? = null,
    val leftAt: Instant? = null,
    val currentModel: OpenCodeModelRef? = null,
    val pendingModel: OpenCodeModelRef? = null,
    val shells: List<OpenCodeShell> = emptyList(),
    val queued: List<OpenCodePendingInput> = emptyList(),
    val positions: Map<String, Int> = emptyMap(),
) {
    /** iOS `pruneRequests(where:)`. */
    fun pruningRequests(where: (sessionID: String, isPermission: Boolean) -> Boolean): SessionSwitchSnapshot = copy(
        permissions = permissions.filterNot { where(it.sessionID, true) },
        forms = forms.filterNot { where(it.sessionID, false) },
    )

    /** iOS `restore(into:)`. */
    fun restore(into: SessionDetail): SessionDetail = into.copy(
        familySessions = family,
        familyReadAt = familyReadAt,
        permissionRequests = permissions,
        formRequests = forms,
        hasLoadedPendingRequests = hasLoadedRequests,
        currentModel = pendingModel ?: currentModel ?: into.currentModel,
        pendingModel = pendingModel,
        shells = shells,
        queued = queued,
    )

    /** iOS `restoreSessionData(into:)` (a family member picked in place). */
    fun restoreSessionData(into: SessionDetail): SessionDetail = restore(into)

    companion object {
        /** iOS `SessionFeature.SwitchSnapshot(_ detail:)`: nil for a draft. */
        fun of(detail: SessionDetail): SessionSwitchSnapshot? {
            val session = detail.session ?: return null
            return SessionSwitchSnapshot(
                session = session,
                family = detail.familySessions,
                familyReadAt = detail.familyReadAt,
                permissions = detail.permissionRequests,
                forms = detail.formRequests,
                hasLoadedRequests = detail.hasLoadedPendingRequests,
                seenItemIDs = detail.cacheableItems.map { it.id }.toSet(),
                leftAt = detail.leftAt,
                currentModel = detail.currentModel,
                pendingModel = detail.pendingModel,
                shells = detail.shells,
                queued = detail.queued,
            )
        }
    }
}

/** TODO(merge): replace with `SessionFeature.Action.Delegate` (what a session screen asks Home to do). */
sealed interface SessionDelegate {
    data class OpenSession(val session: OpenCodeSession) : SessionDelegate
    data class OpenSubagent(val session: OpenCodeSession) : SessionDelegate
    data class ResolveSessionLinks(val ids: List<String>) : SessionDelegate
    data class OpenSessionLink(val id: String) : SessionDelegate
    data object CallRequested : SessionDelegate
    data class TogglePin(val sessionID: String) : SessionDelegate
    data class SetPinned(val session: OpenCodeSession, val pinned: Boolean) : SessionDelegate
    data class OpenDraft(val directory: String?) : SessionDelegate
    data class ToggleMute(val sessionID: String) : SessionDelegate
    data class KeepDraft(val target: SharedDraftTarget, val draft: SessionDraftState) : SessionDelegate
    data class ToggleRead(val sessionID: String) : SessionDelegate
    data class DeleteSession(val sessionID: String) : SessionDelegate
    data object ReportProblem : SessionDelegate
}

/** iOS `SessionFeature.AuthoritativeRefresh.Reason` values Home reacts to. TODO(merge). */
enum class SessionRefreshReason { Open, Resume, Reconnect, Other }

/**
 * Session screen actions Home observes after the screen has reduced them (iOS matches on
 * `.sessionDetail(.presented(<SessionFeature.Action>))`). TODO(merge): match the real actions.
 */
sealed interface SessionScreenEvent {
    data object Task : SessionScreenEvent
    /** An accepted send (`.sendTapped` / `.browser(.askSent)` leaving the draft empty). */
    data object SendAccepted : SessionScreenEvent
    data class RefreshRequested(val reason: SessionRefreshReason) : SessionScreenEvent
    data class VisibilityChanged(val isVisible: Boolean) : SessionScreenEvent
    data class ViewAcknowledged(val sessionID: String, val idle: Long) : SessionScreenEvent
    data class FamilyPicked(val sessionID: String) : SessionScreenEvent
    data class FamilyLoaded(val family: List<OpenCodeSession>) : SessionScreenEvent
    data class FamilySessionDeleted(val sessionID: String) : SessionScreenEvent
    data class AcceptedTranscriptUpdated(val sessionID: String, val items: List<OpenCodeTranscriptItem>) : SessionScreenEvent
    data class ModelApplied(val sessionID: String, val model: OpenCodeModelRef) : SessionScreenEvent
    data class SessionCreated(val session: OpenCodeSession) : SessionScreenEvent
    data class DraftChanged(val draft: SessionDraftState) : SessionScreenEvent
    data class Delegate(val delegate: SessionDelegate) : SessionScreenEvent
}

/**
 * What Home sends an open screen (iOS `.sessionDetail(.presented(…))` / `.subagentStack(.element)`
 * sends). TODO(merge): send the real `SessionFeature.Action`s.
 */
sealed interface SessionScreenMessage {
    data object FamilyRefreshRequested : SessionScreenMessage
    data class FamilyRunStateChanged(val sessionID: String, val isRunning: Boolean) : SessionScreenMessage
    data class FamilySessionRenamed(val sessionID: String, val title: String) : SessionScreenMessage
    data class FamilySessionDeleted(val sessionID: String) : SessionScreenMessage
    data class ContentBatch(val sessionID: String, val eventCount: Int) : SessionScreenMessage
    data class MessageInvalidated(val sessionID: String) : SessionScreenMessage
    data class RevertChanged(val sessionID: String, val messageID: String?, val committed: Boolean) : SessionScreenMessage
    data class InputQueueChanged(val deliveredID: String?) : SessionScreenMessage
    data class InputEnqueued(val input: OpenCodePendingInput?) : SessionScreenMessage
    data class InputCancelled(val inputID: String) : SessionScreenMessage
    data object RequestsChanged : SessionScreenMessage
    data class ShellStarted(val shell: OpenCodeShell) : SessionScreenMessage
    data class ShellEnded(val shellID: String) : SessionScreenMessage
    data class SessionSnapshotUpdated(val session: OpenCodeSession) : SessionScreenMessage
    data class ExternalRunStateChanged(val isRunning: Boolean) : SessionScreenMessage
    data class RequestsConnectionChanged(val connected: Boolean) : SessionScreenMessage
    data class EventStreamConnected(val serverID: String) : SessionScreenMessage
    data object VisibleScreenChanged : SessionScreenMessage
}

/** Where shared items go. TODO(merge): replace with `:feature:system`'s `SharedDraftTarget`. */
sealed interface SharedDraftTarget {
    data class Session(val id: String) : SharedDraftTarget
    data object NewSession : SharedDraftTarget

    val sessionID: String? get() = (this as? Session)?.id

    companion object {
        fun of(sessionID: String?): SharedDraftTarget = sessionID?.let(::Session) ?: NewSession
    }
}

/** A linked session ID's answer (iOS `SessionLinkTarget`). TODO(merge): use `:feature:transcript`'s. */
sealed interface SessionLinkTarget {
    data object Resolving : SessionLinkTarget
    data class Session(val title: String, val parentID: String?) : SessionLinkTarget
    data object Missing : SessionLinkTarget

    companion object {
        fun of(session: OpenCodeSession): SessionLinkTarget = Session(session.title, session.parentID)
    }
}

/** The drawer button's dot (iOS `DrawerBadge`, `SessionSheetView.swift`). TODO(merge): session area owns it. */
enum class DrawerBadge { Attention, Unread }
