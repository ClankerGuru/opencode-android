package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.feature.drawer.bridge.NotificationPermission
import ai.opencode.feature.drawer.bridge.DictationCommand
import ai.opencode.feature.drawer.bridge.ReceivedShare
import ai.opencode.feature.drawer.bridge.SessionDetail
import ai.opencode.feature.drawer.bridge.SessionDraftState
import ai.opencode.feature.drawer.bridge.SessionRefreshReason
import ai.opencode.feature.drawer.bridge.SessionScreenEvent
import ai.opencode.feature.drawer.bridge.SessionScreenMessage
import ai.opencode.feature.drawer.bridge.SessionSwitchSnapshot
import ai.opencode.feature.drawer.bridge.SharedDraftTarget
import ai.opencode.feature.drawer.bridge.SystemActionDestination
import ai.opencode.feature.drawer.bridge.VoiceCallCommand
import ai.opencode.feature.drawer.bridge.WhatsNewAction
import ai.opencode.feature.drawer.bridge.WhatsNewState
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.drawer.notices.InAppNotice
import ai.opencode.feature.drawer.switcher.SessionSwitcherFeature
import ai.opencode.feature.drawer.bridge.SessionFeatureLimits
import ai.opencode.feature.session.SessionFeature
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.OpenCodeTranscriptWindow
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeNotifyPluginStatus
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.SessionActivity
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Port of iOS `HomeFeature` (`OpenCode/Drawer/HomeFeature.swift` and its `HomeFeature+*.swift`
 * extensions): one server's Home. The open session ([State.sessionDetail], a draft when none is
 * open) under the session drawer, pushed subagents, Catch Up, Settings, In-App Notices and the
 * server's live list (REST snapshots authoritative, SSE projecting).
 *
 * The reducer body is assembled in `HomeFeatureComposition.kt`; Home's own actions route through
 * `HomeFeatureCore.kt`. Child features (`HomeConnectionFeature`, `HomeNotificationsFeature`,
 * `HomeSettingsFeature`, `SessionSwitcherFeature`, `CatchUpFeature`) are scoped there.
 *
 * Session screens are [SessionDetail] stand-ins wrapping the stub `SessionFeature.State` until
 * `:feature:session` ports the fields Home coordinates (see `bridge/`).
 */
class HomeFeature : Reducer<HomeFeature.State, HomeFeature.Action> {

    /**
     * Mirrors of iOS `@Shared` keys ([pinnedSessionKeys], [lastViewedSessionIDs], [sessionReadDates],
     * [manuallyUnreadSessionKeys], [mutedSessionKeys], [isSidebarShown], [initializedInboxServerIDs])
     * are plain values so state comparisons and views see their changes; the reducer writes each
     * through to its shared key (`HomeFeatureDrawerState.kt`). The big caches
     * (`cachedHomeSnapshots`, `cachedTranscriptSnapshots`) are read from their keys on demand.
     *
     * Build one with `State(server, snapshot, loaded)` (iOS `init(server:snapshot:loaded:)`).
     */
    data class State(
        val connection: HomeConnectionFeature.State,
        val notificationState: HomeNotificationsFeature.Status = HomeNotificationsFeature.Status(),
        /** The server being connected to from the drawer, while this Home stays up until that server's Home replaces it. */
        val switchingTo: OpenCodeServer? = null,
        val projects: List<OpenCodeProject> = emptyList(),
        val sessions: List<OpenCodeSession> = emptyList(),
        val activeSessionIDs: Set<String> = emptySet(),
        /**
         * Known `childID → parentID` links. A parent never changes, so links are merged in from
         * snapshots, `session.created` and the families this Home has read, never replaced.
         */
        val activeSessionParents: Map<String, String> = emptyMap(),
        /**
         * Bumps only when cached transcript, active run state, or snapshot state changes for the
         * Catch Up preview session ([catchUpPreviewID]). Maintained after each reduce.
         */
        val previewRevision: Int = 0,
        val search: String = "",
        val sessionSwitcher: SessionSwitcherFeature.State = SessionSwitcherFeature.State(),
        val transcriptCache: Map<String, List<OpenCodeTranscriptItem>> = emptyMap(),
        /** Cached transcripts being read again (`refreshCachedTranscript`). */
        val refreshingTranscripts: Set<String> = emptySet(),
        /**
         * Cached sessions whose background refresh was cancelled or skipped while a foreground
         * transcript read had the connection, oldest first, bounded to [dirtyCachedTranscriptLimit].
         */
        val dirtyCachedTranscriptIDs: List<String> = emptyList(),
        /** When each cached transcript was last read, so a session already fresh isn't read again. */
        val transcriptReadAt: Map<String, Instant> = emptyMap(),
        /** An uncached session's transcript being read for the tap that opened it (`CancelID.selectedTranscript`). */
        val selectedTranscriptFetch: SelectedTranscriptFetch? = null,
        /** Last-known chrome for the last few visited sessions, scoped to this server. */
        val sessionSnapshots: Map<String, SessionSwitchSnapshot> = emptyMap(),
        /** Model picks waiting for the next send into a session not on screen yet. */
        val waitingPicks: Map<String, OpenCodeModelRef> = emptyMap(),
        val sessionSnapshotOrder: List<String> = emptyList(),
        val attentionSessionIDs: Set<String> = emptySet(),
        /** Runs that started (true) or ended (false) since the last snapshot landed, re-applied over the next one. */
        val runChangesDuringRefresh: Map<String, Boolean> = emptyMap(),
        /** Mirror of `SharedKeys.pinnedSessionKeys`. */
        val pinnedSessionKeys: List<String> = emptyList(),
        /** Mirror of `SharedKeys.lastViewedSessionIDs`. */
        val lastViewedSessionIDs: Map<String, String> = emptyMap(),
        /** Mirror of `SharedKeys.sessionReadDates`. */
        val sessionReadDates: Map<String, Instant> = emptyMap(),
        /** Mirror of `SharedKeys.manuallyUnreadSessionKeys`. */
        val manuallyUnreadSessionKeys: Set<String> = emptySet(),
        /** Mirror of `SharedKeys.mutedSessionKeys`. */
        val mutedSessionKeys: Set<String> = emptySet(),
        /** Mirror of `SharedKeys.sidebarShown`: beside a wide window's sidebar, whether it shows. */
        val isSidebarShown: Boolean = true,
        /** Mirror of `SharedKeys.initializedInboxServerIDs`. */
        val initializedInboxServerIDs: Set<String> = emptySet(),
        /** Device-local read receipts not yet in an authoritative snapshot: a stale list must not resurrect unread. */
        val locallyViewedMilliseconds: Map<String, Long> = emptyMap(),
        val catchUpState: CatchUpFeature.State = CatchUpFeature.State(),
        /** Sends the server's session list has not reflected yet. */
        val localActivity: Map<String, LocalActivity> = emptyMap(),
        val pendingDeletion: OpenCodeSession? = null,
        val deletionError: String? = null,
        val recentlyDeletedIDs: Set<String> = emptySet(),
        /** Settings, pushed full screen over the drawer and the session; Back returns to whichever was showing. */
        val isSettingsShown: Boolean = false,
        val sessionDetail: SessionDetail? = null,
        /** A subagent waiting for its parent to open under it (`sessionTapped`). */
        val pendingSubagentPush: OpenCodeSession? = null,
        /** Subagents pushed over [sessionDetail], bottom first; keyed by their (negative) `navigationID`. */
        val subagentStack: List<SessionDetail> = emptyList(),
        /** Sessions looked up for links in transcripts (`SessionLink`), by ID. */
        val linkedSessions: Map<String, OpenCodeSession> = emptyMap(),
        val missingLinkedSessionIDs: Set<String> = emptySet(),
        /** Linked IDs being fetched (at most `linkedSessionFetchLimit`), and those waiting their turn, oldest first. */
        val linkedSessionFetches: Set<String> = emptySet(),
        val linkedSessionQueue: List<String> = emptyList(),
        /** A tapped link being looked up, and the screen it was tapped on. */
        val pendingLinkedSessionOpen: PendingLinkedSessionOpen? = null,
        val whatsNew: WhatsNewState = WhatsNewState(),
        /** Bumped when an action takes the screen to the session from over the open drawer; the drawer closes as it changes. */
        val drawerCloseRequests: Int = 0,
        /** A computer just paired: the drawer opens on its sessions. Holds the [detailGeneration] it was asked on. */
        val drawerOpenRequest: Int? = null,
        /** Items from the share sheet waiting for their composer (iOS `sharedDrafts`). */
        val sharedDrafts: Map<SharedDraftTarget, SessionDraftState> = emptyMap(),
        /** Where arriving shares are taking the screen. */
        val shareNavigationTarget: SharedDraftTarget? = null,
        /**
         * Composers parked by session while their screen is away. Stand-in for iOS
         * `composerPersistence` (persisted drafts). TODO(merge): `:feature:composer`'s persistence.
         */
        val parkedDrafts: Map<String, SessionDraftState> = emptyMap(),
        /** False while a cold launch without a cached list waits for its first snapshot. */
        val snapshotLoaded: Boolean = true,
        /** Whether Voice is set up, as last observed. */
        val systemVoiceAvailable: Boolean = false,
        /** In-App Notices (Experimental): the drawer button's label for another session's update, while it shows. */
        val inAppNotice: InAppNotice? = null,
        val inAppNoticeGeneration: Int = 0,
        /** Events already shown or passed over, newest last, so a repeat never shows again. */
        val noticedEvents: List<String> = emptyList(),
        /** Whether the drawer last arrived open. */
        val isDrawerOpen: Boolean = false,
        /** Whether the drawer button can show its label: not beside a wide window's sidebar. */
        val showsDrawerLabel: Boolean = true,
        /** How far this connection (or return to the app) has read Home. */
        val noticeReadiness: NoticeReadiness = NoticeReadiness.unsettled,
        /** Counts opened details so each presented screen has its own identity. */
        val detailGeneration: Int = 0,
        /** Pushed subagents' identities, counted apart (and negative). */
        val pushedDetailCount: Int = 0,
        /** Why the next screen change happens, when the action that asks for it isn't the one that makes it. */
        val nextMoveReason: String? = null,
    ) {
        enum class NoticeReadiness { unsettled, listed, settled }

        /** A drawer row's status. */
        enum class Indicator {
            attention, running, unread, none;

            /** The gauntlet pairs fixture actions with these names. */
            val labMarkName: String
                get() = when (this) {
                    attention -> "attention"
                    running -> "running"
                    unread, none -> "idle"
                }
        }

        /** Read/mute state and busy sessions, snapshotted once for bulk loops over `sessions`. */
        data class StatusSnapshot(
            val serverID: String,
            val showsActivity: Boolean,
            val busy: Set<String>,
            val attention: Set<String>,
            val mutedKeys: Set<String>,
            val manuallyUnreadKeys: Set<String>,
            val readDates: Map<String, Instant>,
        ) {
            fun key(id: String): String = "$serverID|$id"

            fun isMuted(id: String): Boolean = mutedKeys.isNotEmpty() && key(id) in mutedKeys

            fun isUnread(session: OpenCodeSession): Boolean {
                if (manuallyUnreadKeys.isNotEmpty() && key(session.id) in manuallyUnreadKeys) return true
                session.idleMilliseconds?.let { return it > (session.viewedMilliseconds ?: -1) }
                return readDates[key(session.id)]?.let { it < session.updatedAt } ?: true
            }

            fun indicator(session: OpenCodeSession): Indicator {
                if (!showsActivity) return Indicator.none
                if (session.id in attention) return Indicator.attention
                if (session.id in busy) return Indicator.running
                // A muted session's read state is kept, just never shown.
                if (isUnread(session) && !isMuted(session.id)) return Indicator.unread
                return Indicator.none
            }

            /** Idle with something new, or waiting on the reader. Muted sessions never do, even while waiting. */
            fun needsCatchUp(session: OpenCodeSession): Boolean {
                if (isMuted(session.id)) return false
                return when (indicator(session)) {
                    Indicator.attention, Indicator.unread -> true
                    Indicator.running, Indicator.none -> false
                }
            }
        }

        /** What the reader views (iOS `Viewing`): equal when the threads are. */
        data class Viewing(val sessionID: String? = null, val rootID: String? = null) {
            val threads: Set<String> get() = setOfNotNull(sessionID, rootID)
            override fun equals(other: Any?): Boolean =
                other is Viewing && ((sessionID == other.sessionID && rootID == other.rootID) || threads == other.threads)
            override fun hashCode(): Int = threads.hashCode()
        }

        data class LocalActivity(
            /** The server's `updatedAt` before the send. Any newer server time means it has seen the send. */
            val serverUpdatedAt: Instant,
            val sentAt: Instant,
        )

        data class CatchUpPreviewSlice(
            val sessionID: String,
            val items: List<OpenCodeTranscriptItem>?,
            val isRunning: Boolean,
            val snapshot: SessionSwitchSnapshot?,
            val activeFamilyIDs: Set<String>,
        )

        /** What `knownParents` merges (iOS `ParentSources`). */
        data class ParentSources(
            val activeSessionParents: Map<String, String>,
            val sessionSnapshots: Map<String, SessionSwitchSnapshot>,
            val family: List<OpenCodeSession>,
        ) {
            val knownParents: Map<String, String>
                get() {
                    val parents = activeSessionParents.toMutableMap()
                    for (snapshot in sessionSnapshots.values) {
                        for (session in snapshot.family) {
                            val parent = session.parentID ?: continue
                            if (parents[session.id] != parent) parents[session.id] = parent
                        }
                    }
                    for (session in family) {
                        val parent = session.parentID ?: continue
                        if (parents[session.id] != parent) parents[session.id] = parent
                    }
                    return parents
                }

            /** The running sessions and every ancestor they keep busy. */
            fun busy(activeSessionIDs: Set<String>): Set<String> =
                if (activeSessionIDs.isEmpty()) emptySet() else SessionActivity.ancestors(activeSessionIDs, knownParents)

            companion object {
                val empty = ParentSources(emptyMap(), emptyMap(), emptyList())
            }
        }

        /** One server's Home: replacing it with another server's ends this one's effects. */
        val id: String get() = server.persistenceID

        val server: OpenCodeServer get() = connection.server

        fun withServer(server: OpenCodeServer): State = copy(connection = connection.copy(server = server))

        /**
         * Like the TUI and the desktop app, a root is busy while it or anything in its family runs:
         * subagents moved to the background keep running their own sessions. Shells don't count.
         */
        val busySessionIDs: Set<String>
            get() = if (activeSessionIDs.isEmpty()) emptySet() else parentSources.busy(activeSessionIDs)

        /** Every parent link Home knows, including the open and remembered sessions' families. */
        val knownParents: Map<String, String> get() = parentSources.knownParents

        val parentSources: ParentSources
            get() = ParentSources(activeSessionParents, sessionSnapshots, sessionDetail?.familySessions.orEmpty())

        /** Whether a running session's ancestry reaches a listed root (or it is one). */
        fun isAttributed(sessionID: String, parents: Map<String, String>? = null): Boolean {
            val links = parents ?: knownParents
            var id = sessionID
            val seen = hashSetOf(id)
            while (true) {
                val parent = links[id] ?: break
                if (!seen.add(parent)) break
                id = parent
            }
            return sessions.any { it.id == id }
        }

        /** Running sessions whose known parent chain does not yet reach a listed root. */
        val unattributedActiveSessionIDs: Set<String>
            get() {
                if (activeSessionIDs.isEmpty()) return emptySet()
                val parents = knownParents
                return activeSessionIDs.filterTo(LinkedHashSet()) { !isAttributed(it, parents) }
            }

        /** Top-most busy ancestors and busy listed sessions (iOS `observedBusyTopIDs`). */
        val busyTopIDs: Set<String>
            get() {
                if (activeSessionIDs.isEmpty()) return emptySet()
                val parents = knownParents
                val listed = sessions.mapTo(HashSet()) { it.id }
                return SessionActivity.ancestors(activeSessionIDs, parents).filterTo(LinkedHashSet()) {
                    parents[it] == null || it in listed
                }
            }

        val catchUpPreviewID: String? get() = catchUp?.queue?.drop(1)?.firstOrNull()

        /** The Catch Up preview's inputs ([previewRevision] bumps when these change for the same session). */
        val catchUpPreviewSlice: CatchUpPreviewSlice?
            get() {
                val previewID = catchUpPreviewID ?: return null
                val snapshot = sessionSnapshots[previewID]
                val familyIDs = snapshot?.family?.mapTo(HashSet()) { it.id } ?: setOf(previewID)
                return CatchUpPreviewSlice(
                    sessionID = previewID,
                    items = transcriptCache[previewID],
                    isRunning = previewID in activeSessionIDs,
                    snapshot = snapshot,
                    activeFamilyIDs = if (activeSessionIDs.isEmpty()) emptySet() else activeSessionIDs intersect familyIDs,
                )
            }

        val credentialsRejected: Boolean get() = connection.credentialsRejected
        val isConnected: Boolean get() = connection.isConnected
        val connectionStatus: HomeConnectionFeature.State.ConnectionStatus get() = connection.connectionStatus
        val connectionErrorMessage: String? get() = connection.connectionErrorMessage
        val hasConnected: Boolean get() = connection.hasConnected
        val isStreaming: Boolean get() = connection.isStreaming
        val isEventStreamLive: Boolean get() = connection.isEventStreamLive
        val snapshotFailures: Int get() = connection.snapshotFailures
        val lastKnownActivityDeadline: Instant? get() = connection.lastKnownActivityDeadline

        val notificationPermission: NotificationPermission? get() = notificationState.notificationPermission
        val isRequestingNotifications: Boolean get() = notificationState.isRequestingNotifications
        val notificationSetupError: String? get() = notificationState.notificationSetupError
        val notifyPlugin: HomeNotificationsFeature.NotifyPluginCheck? get() = notificationState.notifyPlugin

        /** Slack-style catch-up: unread and waiting sessions as a card stack. */
        val catchUp: CatchUpFeature.Stack? get() = catchUpState.stack

        fun withCatchUp(stack: CatchUpFeature.Stack?): State = copy(catchUpState = catchUpState.copy(stack = stack))

        /** Mark All as Read receipts in flight, with what each session looked like before. */
        val markAllReceipts: Map<String, CatchUpFeature.ReadBackup> get() = catchUpState.markAllReceipts

        fun withMarkAllReceipts(receipts: Map<String, CatchUpFeature.ReadBackup>): State =
            copy(catchUpState = catchUpState.copy(markAllReceipts = receipts))

        /** Whether the pairing's drawer open still applies. */
        val opensDrawerForPairing: Boolean
            get() = drawerOpenRequest == detailGeneration &&
                sessionDetail?.isDraft != false &&
                !presentsOverHome

        /**
         * Home has moved toward another screen, or shows something over the session: Catch Up,
         * Settings, What's New, the Session Switcher, a push, a pending open, or a confirmation.
         */
        val presentsOverHome: Boolean
            get() = catchUp != null ||
                isSettingsShown ||
                whatsNew.isPresented ||
                sessionSwitcher.isPresented ||
                pendingDeletion != null ||
                selectedTranscriptFetch != null ||
                pendingSubagentPush != null ||
                pendingLinkedSessionOpen != null ||
                subagentStack.isNotEmpty()

        /** Settings pushed as Settings, never as What's New. */
        fun showingSettings(): State = copy(whatsNew = whatsNew.copy(isPresented = false), isSettingsShown = true)

        /** The push closed with What's New open or read in it. */
        val closesWhatsNew: Boolean get() = !isSettingsShown && (whatsNew.isPresented || whatsNew.wasViewed)

        /** A reconnect, a server's first connection, or a return to the app: the next list and attention read set the baseline quietly. */
        fun unsettlingNotices(): State = copy(noticeReadiness = NoticeReadiness.unsettled)

        /** The screen on top: the last pushed subagent, else the session. */
        val topDetail: SessionDetail? get() = subagentStack.lastOrNull() ?: sessionDetail

        /** Whether an initial or foreground transcript request is in flight. */
        val isInitialTranscriptInFlight: Boolean
            get() {
                val fetch = selectedTranscriptFetch
                if (fetch != null && fetch.transition == detailGeneration) return true
                // TODO(merge): also the top screen's `.open`/`.resume`/`.reconnect` refresh in flight
                // (`topDetail?.refresh`), once SessionFeature reads its own transcript.
                return false
            }

        /** What the reader is looking at: the screen on top, the session it shows, and Catch Up's card. */
        val visibleScreen: VisibleScreen
            get() = VisibleScreen(
                navigationID = topDetail?.navigationID,
                sessionID = topDetail?.session?.id,
                stackDepth = subagentStack.size,
                catchUpOpen = catchUp != null,
                catchUpCard = catchUp?.queue?.firstOrNull(),
            )

        /** Projects ordered by the newest session they contain. */
        val projectsByRecency: List<OpenCodeProject>
            get() {
                if (projects.size <= 1) return projects
                val lastUsed = HashMap<String, Instant>(projects.size)
                for (session in sessions) {
                    val existing = lastUsed[session.projectID]
                    if (existing != null && existing >= session.updatedAt) continue
                    lastUsed[session.projectID] = session.updatedAt
                }
                return projects.sortedByDescending { lastUsed[it.id] ?: Instant.MIN }
            }

        /** The model a new session inherits: the newest listed session's (iOS `lastUsedModel`). */
        val lastUsedModel: OpenCodeModelRef?
            get() = sessions.filter { it.model != null }.maxByOrNull { it.updatedAt }?.model

        companion object {
            /**
             * iOS `init(server:snapshot:loaded:)`: the cached or loaded list, the transcript cache
             * restored, and the remembered session (or a draft) open. Reads shared keys, so call it
             * from a reducer or effect (a dependency context).
             */
            operator fun invoke(server: OpenCodeServer, snapshot: OpenCodeHomeSnapshot, loaded: Boolean = true): State {
                val base = State(
                    connection = HomeConnectionFeature.State(server = server),
                    snapshotLoaded = loaded,
                    projects = snapshot.projects,
                    sessions = snapshot.sessions,
                    activeSessionIDs = snapshot.activeSessionIDs,
                    activeSessionParents = snapshot.activeSessionParents ?: emptyMap(),
                    pinnedSessionKeys = shared(SharedKeys.pinnedSessionKeys).value,
                    lastViewedSessionIDs = shared(SharedKeys.lastViewedSessionIDs).value,
                    sessionReadDates = shared(SharedKeys.sessionReadDates).value,
                    manuallyUnreadSessionKeys = shared(SharedKeys.manuallyUnreadSessionKeys).value,
                    mutedSessionKeys = shared(SharedKeys.mutedSessionKeys).value,
                    isSidebarShown = shared(SharedKeys.sidebarShown).value,
                    initializedInboxServerIDs = shared(SharedKeys.initializedInboxServerIDs).value,
                )
                return base.restoringTranscriptCache().restoringInitialSession()
            }

            /** An inert Home for a view whose parent no longer holds one (never reduced). */
            fun placeholder(server: OpenCodeServer): State = State(connection = HomeConnectionFeature.State(server = server))
        }
    }

    /** The detail move an uncached tap's transcript read belongs to. */
    data class SelectedTranscriptFetch(
        val sessionID: String,
        /** The detail move it belongs to ([State.detailGeneration]). */
        val transition: Int,
    )

    /** The row a pending deletion hid: a failure only needs to put the row back. */
    data class DeletionBackup(
        val session: OpenCodeSession,
        val sessionIndex: Int,
        val wasActive: Boolean,
        val neededAttention: Boolean,
        val runChangeDuringRefresh: Boolean?,
        /** The draft that replaced the open session, if it was open. */
        val replacementDraftID: Int? = null,
        val openItems: List<OpenCodeTranscriptItem>,
    )

    data class VisibleScreen(
        val navigationID: Int?,
        val sessionID: String?,
        val stackDepth: Int,
        /** iOS `catchUpCard: String??`: whether Catch Up is open, and its open card (null once it ran out). */
        val catchUpOpen: Boolean,
        val catchUpCard: String?,
    )

    /** A tapped session link waiting on its lookup. */
    data class PendingLinkedSessionOpen(val id: String, val screen: Screen?) {
        /** The screen on top when it was tapped. */
        data class Screen(val sessionID: String?, val navigationID: Int, val visit: Int, val depth: Int)
    }

    /** What Home asks its parent (`AppFeature`) to do; iOS handles these cases in `AppFeature`. */
    sealed interface DelegateAction {
        /** Returns to the server picker. */
        data object ServerBadgeTapped : DelegateAction
        /** Connects to another saved server. */
        data class ServerSwitchTapped(val server: OpenCodeServer) : DelegateAction
        /** Leave this server to pick another. */
        data object ServersRequested : DelegateAction
        /** Renames a saved server in place, without reconnecting (Home also takes the name). */
        data class ServerRenamed(val server: OpenCodeServer, val name: String) : DelegateAction
        /** Opens a saved server's address, credentials and headers for editing. */
        data class EditServerRequested(val server: OpenCodeServer) : DelegateAction
        /** Forgets a saved server other than the one in use. */
        data class ServerRemovalConfirmed(val server: OpenCodeServer) : DelegateAction
        /** Opens the scanner for another server's pairing code. */
        data object PairServerRequested : DelegateAction
        /** Opens the form for a server entered by address. */
        data object AddServerRequested : DelegateAction
    }

    sealed interface Action {
        data class SessionSwitcher(val action: SessionSwitcherFeature.Action) : Action
        data class CatchUp(val action: CatchUpFeature.Action) : Action
        data class WhatsNew(val action: WhatsNewAction) : Action
        data class Settings(val action: HomeSettingsFeature.Action) : Action
        /** iOS `.binding(.set(\.search, …))`. */
        data class SearchChanged(val text: String) : Action
        /** iOS `.binding(.set(\.isSettingsShown, …))`: the view popped (or pushed) Settings. */
        data class SettingsShownChanged(val isShown: Boolean) : Action
        data object Task : Action
        data object TranscriptCacheFlushRequested : Action
        data object ConnectionLost : Action
        data class EventStreamFailed(val error: OpenCodeClientError) : Action
        data class ServerConnected(val server: OpenCodeServer) : Action
        data object SnapshotFailed : Action
        /** A failed snapshot's backoff elapsed while the stream stayed live. */
        data object SnapshotRetryRequested : Action
        /** Last-known running state is too old to show while reconnecting. */
        data object LastKnownActivityExpired : Action
        data object RetryConnectionTapped : Action
        /** The network path came back or moved to another interface. */
        data object NetworkRerouted : Action
        data class ServerEvent(val event: OpenCodeServerEvent) : Action
        /** Handled by `AppFeature`. */
        data class Delegate(val action: DelegateAction) : Action
        data object NotificationSettingsRequested : Action
        data class NotificationPermissionLoaded(val permission: NotificationPermission) : Action
        data object EnableNotificationsTapped : Action
        data class NotificationPermissionRequested(val permission: NotificationPermission) : Action
        data class NotificationSettingsOpened(val opened: Boolean) : Action
        data object NotifyPluginCheckRequested : Action
        data class NotifyPluginChecked(val status: OpenCodeNotifyPluginStatus?) : Action
        /** Opens a draft whose prompt asks OpenCode to install the plugin. */
        data object NotifyPluginSetUpTapped : Action
        data object SettingsTapped : Action
        /** The drawer visually reached the other edge: its haptic. */
        data class SessionDrawerArrived(val isOpen: Boolean) : Action
        data object NewSessionTapped : Action
        /** A computer paired for the first time: open the drawer on its sessions. */
        data object PairedComputerOpened : Action
        /** The view opened the drawer `drawerOpenRequest` asked for. */
        data object DrawerOpenRequestHandled : Action
        data class SessionTapped(val sessionID: String) : Action
        data class TogglePin(val sessionID: String) : Action
        data class ToggleMute(val sessionID: String) : Action
        data class MovePinUp(val sessionID: String) : Action
        data class MovePinDown(val sessionID: String) : Action
        data class DrawerPinZoneCrossed(val entered: Boolean) : Action
        data object DrawerPinSlotChanged : Action
        data class DrawerRowDropReleased(val sessionID: String, val pinnedIndex: Int?) : Action
        data class DrawerRowDropped(val sessionID: String, val pinnedIndex: Int?) : Action
        data class DeleteSessionTapped(val sessionID: String) : Action
        data object DeleteSessionCancelled : Action
        data object DeleteSessionConfirmed : Action
        data class DeleteSessionResponse(val sessionID: String, val backup: DeletionBackup, val error: OpenCodeClientError?) : Action
        data object ClearDeletionError : Action
        data class MarkRead(val sessionID: String) : Action
        data class ViewAcknowledged(val sessionID: String, val idle: Long) : Action
        data class MarkUnread(val sessionID: String) : Action
        data class SnapshotRefreshed(val snapshot: OpenCodeHomeSnapshot) : Action
        data class ActiveAncestryResolved(val parents: Map<String, String>) : Action
        data class AttentionRefreshed(val sessionIDs: Set<String>) : Action
        /** The open session's own actions (`ifLet`). */
        data class SessionDetail(val action: PresentationAction<SessionFeature.Action>) : Action
        /**
         * What the open session's screen did that Home coordinates (iOS matches these on
         * `.sessionDetail(.presented(…))`). TODO(merge): fold into [SessionDetail].
         */
        data class SessionDetailEvent(val event: SessionScreenEvent) : Action
        /** A pushed subagent's own actions, by its `navigationID` (`forEach`). */
        data class SubagentStack(val id: Int, val action: SessionFeature.Action) : Action
        /** What a pushed subagent's screen did. TODO(merge): fold into [SubagentStack]. */
        data class SubagentEvent(val id: Int, val event: SessionScreenEvent) : Action
        /**
         * Home telling the screen [navigationID] something (iOS sends a `SessionFeature.Action`).
         * Inert until `:feature:session` handles them. TODO(merge): send the real actions.
         */
        data class ScreenMessage(val navigationID: Int, val message: SessionScreenMessage) : Action
        /** The screen went back until [depth] subagents remain pushed. */
        data class SubagentsPopped(val depth: Int) : Action
        /** A linked session ID looked up for its title. */
        data class LinkedSessionFetched(val id: String, val result: Result<OpenCodeSession>) : Action
        /** A tapped session link the list didn't hold, looked up to open it. */
        data class LinkedSessionOpened(val id: String, val result: Result<OpenCodeSession>) : Action
        /** Items from the share sheet, oldest first, all for this server. */
        data class SharesReceived(val shares: List<ReceivedShare>) : Action
        data class TranscriptPrefetched(val sessionID: String, val items: List<OpenCodeTranscriptItem>) : Action
        /** A cached transcript read again, so it opens up to date. */
        data class CachedTranscriptRefreshed(val sessionID: String, val items: List<OpenCodeTranscriptItem>?) : Action
        /** A finger came down on a drawer row: its tap may follow. */
        data class DrawerRowPressed(val sessionID: String) : Action
        /** The app left the screen: the stream stays live a little longer. */
        data object SceneEnteredBackground : Action
        data class SessionNavigationReady(val sessionID: String) : Action
        data class SelectedTranscriptResponse(
            val sessionID: String,
            val result: Result<List<OpenCodeTranscriptItem>>,
            val pageCursor: OpenCodeTranscriptWindow.PageCursor? = null,
        ) : Action
        data class VoiceCallCommandReceived(val command: VoiceCallCommand) : Action
        data class DictationCommandReceived(val command: DictationCommand) : Action
        /** Shortcuts, a launcher action or a link, for this server; `AppFeature` has fetched any session the list lacked. */
        data class SystemActionOpened(
            val destination: SystemActionDestination,
            val startsCall: Boolean,
            val inBackground: Boolean = false,
        ) : Action
        /** From the view, on appearing and whenever Voice is set up or not: publishes to the system. */
        data class SystemPublishRequested(val voiceAvailable: Boolean) : Action
        /** The drawer button's label held its time and folds back. */
        data class InAppNoticeExpired(val id: Int) : Action
        /** The label: opens its session, or (for several) the view opens the drawer. */
        data object InAppNoticeTapped : Action
        /** From the view: whether the drawer button can show the label. */
        data class DrawerLabelAvailabilityChanged(val shows: Boolean) : Action
        /** Used by playgrounds to swap fixture states in place. */
        data class PlaygroundSetSessionDetail(val detail: ai.opencode.feature.drawer.bridge.SessionDetail) : Action
        data class PlaygroundReplaceState(val state: State) : Action

        companion object {
            val catchUpTapped: Action get() = CatchUp(CatchUpFeature.Action.Tapped)
            fun catchUpCardDismissed(sessionID: String, disposition: CatchUpFeature.Disposition): Action =
                CatchUp(CatchUpFeature.Action.CardDismissed(sessionID, disposition))
            val catchUpUndoTapped: Action get() = CatchUp(CatchUpFeature.Action.UndoTapped)
            val catchUpExitTapped: Action get() = CatchUp(CatchUpFeature.Action.ExitTapped)
            fun catchUpFinished(reducedMotion: Boolean): Action = CatchUp(CatchUpFeature.Action.Finished(reducedMotion))
            val catchUpOpenTapped: Action get() = CatchUp(CatchUpFeature.Action.OpenTapped)
            val catchUpMarkAllReadTapped: Action get() = CatchUp(CatchUpFeature.Action.MarkAllReadTapped)
        }
    }

    /** Cancellation identities (iOS `HomeFeature.CancelID`), shared with `HomeConnectionFeature`. */
    sealed interface CancelID {
        data object Events : CancelID
        data object Refresh : CancelID
        data object ActiveAncestry : CancelID
        data object Attention : CancelID
        data object Prefetch : CancelID
        data object SelectedTranscript : CancelID
        data object DeletionError : CancelID
        data object VoiceCallCommands : CancelID
        data object DictationCommands : CancelID
        data object SnapshotRetry : CancelID
        data object LastKnownActivity : CancelID
        data object NetworkPath : CancelID
        data object InAppNotice : CancelID
        data object LiveUpdatesAfterLeaving : CancelID
        data class CachedTranscript(val sessionID: String) : CancelID
        data object LinkedSessionOpen : CancelID
        data class DeleteSession(val sessionID: String) : CancelID
        data class ViewReceipt(val sessionID: String) : CancelID
    }

    /** The assembled body (`HomeFeatureComposition.kt`). */
    private val body: Reducer<State, Action> by lazy { builtBody() }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = body.reduce(state, action)

    companion object {
        /** Asks the user's own OpenCode to install the plugin; they review it before sending. */
        val notifyPluginSetUpPrompt: String =
            "Set up OpenCode iOS notifications on this server. Add \"${OpenCodeNotifyPluginStatus.PACKAGE_ENTRY}\" " +
                "to the \"plugins\" array of my global OpenCode config (~/.config/opencode/opencode.json or opencode.jsonc, " +
                "whichever exists), keeping every other entry and any comments. Don't restart the server: OpenCode reloads " +
                "config changes on its own. Then confirm a plugin with ID \"${OpenCodeNotifyPluginStatus.PLUGIN_ID}\" is " +
                "active (GET /api/plugin) and tell me if anything went wrong."

        /** Mark All as Read receipts in flight at once. */
        const val receiptConcurrency = CatchUpFeature.receiptConcurrency

        /** iOS `State.credentialsRejectedStatus`. */
        const val credentialsRejectedStatus = 401

        /** How long last-known running state is shown without a live connection. */
        val lastKnownActivityLifetime: Duration = 120.seconds

        /** A cached snapshot's running state may show until this deadline, if it hasn't passed. */
        fun lastKnownActivityDeadline(capturedAt: Instant?, now: Instant): Instant? {
            if (capturedAt == null || capturedAt > now) return null
            val deadline = capturedAt.plusMillis(lastKnownActivityLifetime.inWholeMilliseconds)
            return if (deadline > now) deadline else null
        }

        /** How long the event stream stays live after the app leaves the screen. */
        val liveUpdatesAfterLeaving: Duration = 30.seconds

        /** A cached session's live events, coalesced into one read this late. */
        val cachedTranscriptRefreshDelay: Duration = 2.seconds

        /** Parent links kept beyond those above running sessions. */
        const val parentLinkLimit = 512

        /** Retry delays for a failed snapshot while the stream is live, in seconds. */
        val snapshotRetryDelays: List<Int> = listOf(1, 2, 4, 8, 16, 30)

        /** How many uncached rows to warm after each snapshot. */
        const val prefetchCount = 3

        /** Bounds the in-memory transcripts. */
        const val transcriptMemoryLimit = 8

        /** Bound of [State.dirtyCachedTranscriptIDs]. */
        const val dirtyCachedTranscriptLimit = 16

        /** Lookups in flight at once; the rest wait in `linkedSessionQueue`. */
        const val linkedSessionFetchLimit = 6
    }
}

/**
 * Stamps Home's cached snapshot and times how long last-known running state shows while
 * reconnecting (iOS `\.homeSnapshotDate`). Tests read a fixed instant unless they set one.
 */
object HomeSnapshotDate : DependencyKey<DateClient> {
    override val liveValue: DateClient = DateClient { Instant.now() }
    override val testValue: DateClient = DateClient.constant(Instant.EPOCH)
}

/** iOS `SessionRefreshReason` values that make a foreground read. */
internal val foregroundRefreshReasons = setOf(SessionRefreshReason.Open, SessionRefreshReason.Resume, SessionRefreshReason.Reconnect)

// region State navigation (iOS `HomeFeature.State` methods in `HomeFeature.swift`)

/**
 * Moves shared items into the open composer when it is their target. Catch Up cards are left
 * alone: their composers are not kept.
 */
fun HomeFeature.State.absorbingSharedDrafts(): HomeFeature.State {
    if (sharedDrafts.isEmpty() || catchUp != null) return this
    var s = this
    for (child in s.subagentStack) {
        val key = SharedDraftTarget.of(child.session?.id ?: continue)
        val shared = s.sharedDrafts[key] ?: continue
        s = s.copy(sharedDrafts = s.sharedDrafts - key)
            .updatingSubagent(child.navigationID) { it.copy(draft = it.draft.absorbing(shared)) }
    }
    val detail = s.sessionDetail ?: return s
    val key = SharedDraftTarget.of(detail.session?.id)
    val target = s.shareNavigationTarget
    if (target != null) {
        if (key != target || detail.isDraft != (target == SharedDraftTarget.NewSession)) return s
        s = s.copy(shareNavigationTarget = null)
    }
    val shared = s.sharedDrafts[key] ?: return s
    return s.copy(sharedDrafts = s.sharedDrafts - key).updatingDetail { it.copy(draft = it.draft.absorbing(shared)) }
}

private fun SessionDraftState.absorbing(shared: SessionDraftState): SessionDraftState =
    if (text.isEmpty()) shared else if (shared.text.isEmpty()) this else SessionDraftState("$text\n${shared.text}")

/** Opens the remembered session for this server, else a draft (iOS `restoreInitialSession`). */
fun HomeFeature.State.restoringInitialSession(): HomeFeature.State {
    val rememberedID = lastViewedSessionIDs[server.persistenceID]
    val session = rememberedID?.let { id -> sessions.firstOrNull { it.id == id } }
    if (session != null) {
        return markingReadLocally(session).let {
            it.openingDetail(session, cachedItems = it.cachedTranscriptItems(session), reason = "the app restored it")
        }
    }
    // An unloaded list can't say the session is gone.
    val s = if (snapshotLoaded) updatingLastViewedSessionIDs { it - server.persistenceID } else this
    return s.openingDraft(reason = "the app restored a draft")
}

/** iOS `makeDetail(for:cachedItems:externalTranscript:reason:)`: the next state (outgoing screen remembered) and the new screen. */
fun HomeFeature.State.makeDetail(
    session: OpenCodeSession,
    cachedItems: List<OpenCodeTranscriptItem>? = null,
    externalTranscript: Boolean = false,
    reason: String = "you opened it",
): Pair<HomeFeature.State, SessionDetail> {
    val (begun, transition) = beginningDetailTransition(defaultReason = reason)
    var s = begun
    var detail = SessionDetail.forSession(
        session = session,
        navigationID = transition.navID,
        visit = transition.visit,
        cachedItems = cachedItems,
        externalTranscript = externalTranscript,
        isRunning = session.id in s.activeSessionIDs,
    )
    detail = s.seedingSessionSnapshot(detail)
    detail = detail.copy(
        streamIsHealthy = s.isEventStreamLive,
        seenItemIDs = s.sessionSnapshots[session.id]?.seenItemIDs,
        leftAt = s.sessionSnapshots[session.id]?.leftAt,
    )
    val pick = s.waitingPicks[session.id]
    if (pick != null) {
        s = s.copy(waitingPicks = s.waitingPicks - session.id)
        detail = detail.copy(currentModel = pick, pendingModel = pick)
    }
    detail = s.finishingDetailTransition(detail)
    val (restored, withDraft) = s.restoringPersistedDraft(detail)
    s = restored
    if (cachedItems != null) s = s.touchingCachedTranscript(session)
    // TODO(merge): a live call follows the screen (`voice.retarget`), logging `transition.reason`.
    return s to withDraft
}

/** `sessionDetail = makeDetail(…)`. */
fun HomeFeature.State.openingDetail(
    session: OpenCodeSession,
    cachedItems: List<OpenCodeTranscriptItem>? = null,
    externalTranscript: Boolean = false,
    reason: String = "you opened it",
): HomeFeature.State {
    val (s, detail) = makeDetail(session, cachedItems, externalTranscript, reason)
    return s.copy(sessionDetail = detail)
}

/** iOS `makeDraft(directory:reason:)`. */
fun HomeFeature.State.makeDraft(directory: String? = null, reason: String = "you started a new session"): Pair<HomeFeature.State, SessionDetail> {
    val (begun, transition) = beginningDetailTransition(defaultReason = reason)
    var s = begun
    var projects = s.projectsByRecency
    // The voice agent can ask for a draft in a particular project, or in a folder no project holds yet.
    if (directory != null) {
        val index = projects.indexOfFirst { it.directory == directory }
        if (index >= 0) projects = listOf(projects[index]) + projects.filterIndexed { i, _ -> i != index }
    }
    var draft = SessionDetail.draft(
        draftProject = directory?.let { OpenCodeProject.folder(it, projects) } ?: projects.firstOrNull(),
        projects = projects,
        inheritedModel = s.lastUsedModel,
        navigationID = transition.navID,
        visit = transition.visit,
    )
    draft = s.finishingDetailTransition(draft)
    val (restored, withDraft) = s.restoringPersistedDraft(draft)
    s = restored
    return s to withDraft
}

/** `sessionDetail = makeDraft(…)`. */
fun HomeFeature.State.openingDraft(directory: String? = null, reason: String = "you started a new session"): HomeFeature.State {
    val (s, draft) = makeDraft(directory, reason)
    return s.copy(sessionDetail = draft)
}

/**
 * The server has [model] for [sessionID]: the list's copy says so, so a new session started now
 * inherits it. Its snapshot and a pick waiting for its screen follow.
 */
fun HomeFeature.State.notingModel(model: OpenCodeModelRef, sessionID: String, replacesPick: Boolean = false): HomeFeature.State {
    var s = updatingSession(sessionID) { it.copy(model = model) }
    if (replacesPick || s.waitingPicks[sessionID] == model) s = s.copy(waitingPicks = s.waitingPicks - sessionID)
    s = s.updatingSubagents { child ->
        if (child.session?.id != sessionID) return@updatingSubagents child
        if (replacesPick) child.copy(currentModel = model, pendingModel = null)
        else if (child.pendingModel == null || child.pendingModel == model) child.copy(currentModel = model, pendingModel = null)
        else child
    }
    return s.updatingSnapshot(sessionID) { snapshot ->
        var next = snapshot.copy(
            session = snapshot.session.copy(model = model),
            family = snapshot.family.map { if (it.id == sessionID) it.copy(model = model) else it },
        )
        if (replacesPick || next.pendingModel == model) next = next.copy(pendingModel = null)
        if (next.pendingModel == null) next = next.copy(currentModel = model)
        next
    }
}

internal data class DetailTransition(val navID: Int, val visit: Int, val reason: String, val keepsIdentity: Boolean)

private fun HomeFeature.State.beginningDetailTransition(defaultReason: String): Pair<HomeFeature.State, DetailTransition> {
    // Another session replaces the root: whatever was pushed over it goes.
    var s = poppingSubagents(toDepth = 0).rememberingSession().syncingOutgoingDetailDraft()
    val reason = s.nextMoveReason ?: defaultReason
    // Keeping the identity keeps a live call's or recording's effects alive.
    // TODO(merge): `keepsIdentity` is true while the outgoing screen has a live call or carried dictation.
    val keepsIdentity = false
    // Every move counts, identity kept or not (`selectedTranscriptFetch`).
    val fresh = s.detailGeneration + 1
    s = s.copy(nextMoveReason = null, detailGeneration = fresh)
    val navID = if (keepsIdentity) (s.sessionDetail?.navigationID ?: fresh) else fresh
    return s to DetailTransition(navID, fresh, reason, keepsIdentity)
}

private fun HomeFeature.State.finishingDetailTransition(detail: SessionDetail): SessionDetail =
    // The screen swaps details in place without appearing again, so the new one inherits whether it is on screen.
    detail.copy(isVisible = sessionDetail?.isVisible ?: false)

/** The outgoing screen's composer waits for its session (iOS `syncOutgoingDetailDraft`). */
private fun HomeFeature.State.syncingOutgoingDetailDraft(): HomeFeature.State {
    val detail = sessionDetail ?: return this
    if (catchUp != null || !detail.readsOnView) return this
    return parkingDraft(detail.sessionComposer, detail.session?.id)
}

/** A parked composer comes back with its session (iOS `restorePersistedDraft(into:)`). */
private fun HomeFeature.State.restoringPersistedDraft(detail: SessionDetail): Pair<HomeFeature.State, SessionDetail> {
    val key = detail.session?.id ?: DRAFT_KEY
    val parked = parkedDrafts[key] ?: return this to detail
    return copy(parkedDrafts = parkedDrafts - key) to detail.copy(draft = parked)
}

private const val DRAFT_KEY = "\u0000draft"

fun HomeFeature.State.rememberingSession(): HomeFeature.State = sessionDetail?.let(::remembering) ?: this

fun HomeFeature.State.remembering(detail: SessionDetail): HomeFeature.State {
    val snapshot = SessionSwitchSnapshot.of(detail) ?: return this
    val id = snapshot.session.id
    if (id in recentlyDeletedIDs) return this
    if (sessions.none { it.id == id } && snapshot.session.parentID == null) return this
    val snapshots = sessionSnapshots.toMutableMap()
    snapshots[id] = snapshot
    val rootID = detail.familyRootID
    if (rootID != null && rootID != id) {
        snapshots[rootID]?.let { root ->
            snapshots[rootID] = root.copy(
                family = snapshot.family,
                familyReadAt = snapshot.familyReadAt,
                permissions = snapshot.permissions,
                forms = snapshot.forms,
                hasLoadedRequests = snapshot.hasLoadedRequests,
            )
        }
    }
    val order = (sessionSnapshotOrder - id + id).toMutableList()
    while (order.size > HomeFeature.transcriptMemoryLimit) snapshots.remove(order.removeAt(0))
    return copy(sessionSnapshots = snapshots, sessionSnapshotOrder = order)
}

/**
 * Pushes [session], a subagent of the screen on top, over it. Its first frame comes from what the
 * parent already knows, so nothing new is read to show it.
 */
fun HomeFeature.State.pushingSubagent(session: OpenCodeSession): HomeFeature.State {
    val parent = topDetail ?: return this
    if (parent.session?.id == session.id) return this
    val pushedCount = pushedDetailCount + 1
    var s = copy(pushedDetailCount = pushedCount)
    var detail = SessionDetail.forSession(
        session = session,
        navigationID = -pushedCount,
        cachedItems = parent.familyTranscriptCache[session.id] ?: s.cachedTranscriptItems(session),
        isRunning = session.id in s.activeSessionIDs || session.id in parent.activeFamilySessionIDs,
    )
    detail = s.seedingSessionSnapshot(detail)
    detail = detail.copy(
        streamIsHealthy = s.isEventStreamLive || parent.streamIsHealthy,
        seenItemIDs = s.sessionSnapshots[session.id]?.seenItemIDs,
        leftAt = s.sessionSnapshots[session.id]?.leftAt,
    )
    val pick = s.waitingPicks[session.id]
    if (pick != null) {
        s = s.copy(waitingPicks = s.waitingPicks - session.id)
        detail = detail.copy(currentModel = pick, pendingModel = pick)
    }
    val known = detail.familySessions.mapTo(HashSet()) { it.id }
    detail = detail.copy(
        familySessions = detail.familySessions +
            SessionActivity.descendants(session.id, parent.familySessions).filter { it.id !in known },
    )
    if (detail.familyReadAt == null) detail = detail.copy(familyReadAt = parent.familyReadAt)
    if (!detail.hasLoadedPendingRequests) {
        val members = detail.familySessions.mapTo(HashSet()) { it.id }
        detail = detail.copy(
            permissionRequests = parent.permissionRequests.filter { it.sessionID in members },
            formRequests = parent.formRequests.filter { it.sessionID in members },
            hasLoadedPendingRequests = parent.hasLoadedPendingRequests,
        )
    }
    detail = detail.copy(
        activeFamilySessionIDs = detail.activeFamilySessionIDs + parent.activeFamilySessionIDs,
        callsFromRoot = true,
        isVisible = s.sessionDetail?.isVisible ?: false,
        isSceneActive = s.sessionDetail?.isSceneActive ?: true,
    )
    val (restored, withDraft) = s.restoringPersistedDraft(detail)
    s = restored.copy(subagentStack = restored.subagentStack + withDraft)
    // TODO(merge): `retargetCallToTop(reason: "you opened a subagent")` once voice ports.
    return s.markingTopScreen().absorbingSharedDrafts()
}

/** Only the screen on top of the stack plays a request's arrival. */
private fun HomeFeature.State.markingTopScreen(): HomeFeature.State {
    val covered = subagentStack.isNotEmpty()
    val top = subagentStack.lastOrNull()?.navigationID
    return updatingDetail { it.copy(isCovered = covered) }
        .updatingSubagents { it.copy(isCovered = it.navigationID != top) }
}

/**
 * Takes pushed subagents off down to [toDepth]. Their composers wait for their sessions like shared
 * items, and their transcripts stay with the parent for the next push.
 */
fun HomeFeature.State.poppingSubagents(toDepth: Int): HomeFeature.State {
    val targetDepth = maxOf(toDepth, 0)
    if (subagentStack.size <= targetDepth) return this
    var s = this
    val stack = s.subagentStack.mapIndexed { index, child ->
        if (index >= targetDepth) child.copy(isVisible = false) else child
    }.toMutableList()
    s = s.copy(subagentStack = stack.toList())
    while (s.subagentStack.size > targetDepth) {
        val child = s.subagentStack.last()
        s = s.copy(subagentStack = s.subagentStack.dropLast(1)).remembering(child)
        val sessionID = child.session?.id ?: continue
        s = s.parkingDraft(child.draft, sessionID)
        val items = child.cacheableItems.takeLast(SessionFeatureLimits.initialFetchLimit)
        val parentID = s.subagentStack.lastOrNull()?.navigationID
        s = if (parentID != null) {
            s.updatingSubagent(parentID) { it.copy(familyTranscriptCache = it.familyTranscriptCache + (sessionID to items)) }
        } else {
            s.updatingDetail { it.copy(familyTranscriptCache = it.familyTranscriptCache + (sessionID to items)) }
        }
    }
    // TODO(merge): `retargetCallToTop(reason: "you went back")` once voice ports.
    return s.markingTopScreen()
}

fun HomeFeature.State.pushingPendingSubagent(): HomeFeature.State {
    val child = pendingSubagentPush ?: return this
    if (child.parentID != sessionDetail?.session?.id) return this
    return copy(pendingSubagentPush = null).pushingSubagent(child)
}

/** Shared by the selected detail and Catch Up's inert next-card preview. */
fun HomeFeature.State.seedingSessionSnapshot(detail: SessionDetail): SessionDetail {
    val session = detail.session ?: return detail
    val snapshot = sessionSnapshots[session.id] ?: return detail
    if (snapshot.session.directory != session.directory) return detail
    // Home receives run events even while this session is off screen.
    val restored = snapshot.restore(detail).copy(activeFamilySessionIDs = activeSessionIDs)
    return if (!isConnected) restored.copy(familyReadAt = null) else restored
}

/** Parks [composer] for [sessionID] (or the draft) until its screen comes back (iOS `parkDraft`). */
fun HomeFeature.State.parkingDraft(composer: SessionDraftState?, sessionID: String?): HomeFeature.State {
    val key = sessionID ?: DRAFT_KEY
    if (composer == null || composer.isEmpty) {
        return if (key in parkedDrafts) copy(parkedDrafts = parkedDrafts - key) else this
    }
    return copy(parkedDrafts = parkedDrafts + (key to composer))
}

// endregion

/** What Home's helpers return (iOS helpers mutate `inout State` and return an `Effect`). */
internal typealias HomeReduced = Reduced<HomeFeature.State, HomeFeature.Action>
