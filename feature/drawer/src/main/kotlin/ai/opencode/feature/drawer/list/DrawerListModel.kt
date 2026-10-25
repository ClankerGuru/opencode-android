package ai.opencode.feature.drawer.list

import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.HomeFeature.State.Indicator
import ai.opencode.feature.drawer.pinnedSessions
import ai.opencode.feature.drawer.recentSessions
import ai.opencode.feature.drawer.statusSnapshot
import ai.opencode.feature.drawer.style.DrawerSectionHeaderKind
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant

/*
 * Port of iOS `DrawerListModel.swift`: what the drawer's list shows, built from Home's state in one
 * pass. Rows get these values, never a store. Pure: the list view keys a `LazyColumn` by
 * [DrawerListItem.key] and compares [DrawerListRow]s to decide what recomposes.
 *
 * iOS's `DrawerListStyle` / `DrawerRowStyleSnapshot` (a per-update snapshot of every Tuning Panel
 * value, so UIKit cells never read `TuningStore` while scrolling) has no Android counterpart:
 * `DrawerTunables` are constants here, so rows read them directly.
 */

enum class DrawerListSection { top, pinned, recents }

sealed interface DrawerListItem {
    data object CatchUp : DrawerListItem
    data object WhatsNew : DrawerListItem
    data object DeletionError : DrawerListItem
    data class Header(val kind: DrawerSectionHeaderKind) : DrawerListItem
    data class Session(val id: String) : DrawerListItem
    data object Empty : DrawerListItem

    /** A stable `LazyColumn` key. */
    val key: String
        get() = when (this) {
            CatchUp -> "catch-up"
            WhatsNew -> "whats-new"
            DeletionError -> "deletion-error"
            is Header -> "header-${kind.name}"
            is Session -> "session-$id"
            Empty -> "empty"
        }
}

/** One session row, compared to decide which rows to recompose. */
data class DrawerListRow(
    val session: OpenCodeSession,
    val isPinnedSection: Boolean,
    val selected: Boolean,
    val indicator: Indicator,
    val muted: Boolean,
    val unread: Boolean,
    val isLifted: Boolean,
    val time: String,
    val project: OpenCodeProject?,
    val showsProjectIcons: Boolean,
    val server: OpenCodeServer,
    val reducedMotion: Boolean,
    val increasedContrast: Boolean,
) {
    /** TalkBack's state for the row (iOS `accessibilityValue`). */
    val accessibilityValue: String
        get() {
            val parts = mutableListOf<String>()
            if (isPinnedSection) parts += "Pinned"
            if (muted) parts += "Muted"
            val status = when (indicator) {
                Indicator.attention -> "Needs your input"
                Indicator.running -> "Working"
                Indicator.unread -> "Unread"
                Indicator.none -> ""
            }
            if (status.isNotEmpty()) parts += status
            if (time.isNotEmpty()) parts += time
            return parts.joinToString(", ")
        }
}

/** What the list shows besides its rows. */
data class DrawerListShortcuts(
    val catchUpCount: Int = 0,
    /** Catch Up just finished with nothing left: the finish's identity (`CatchUpFeature.State.finishes`), played once. */
    val catchUpFinish: Int? = null,
    val canMarkAllRead: Boolean = false,
    val showsWhatsNew: Boolean = false,
    val catchUpMounted: Boolean = false,
    val whatsNewMounted: Boolean = false,
) {
    val catchUpFinished: Boolean get() = catchUpFinish != null
    val showsCatchUp: Boolean get() = catchUpCount > 0 || catchUpFinished
}

/** The list's view-local inputs beyond Home's state (iOS `DrawerListInputs`, minus UIKit insets). */
data class DrawerListInputs(
    val selectedSessionID: String?,
    val showsProjectIcons: Boolean,
    val reducedMotion: Boolean,
    val increasedContrast: Boolean,
    /** iOS `DrawerTunables.sectionBreak == .header`: section headers always show. */
    val alwaysShowsHeaders: Boolean = true,
    /** iOS `DrawerTunables.rowLayout == .twoLine`: rows name their project even without icons. */
    val twoLineRows: Boolean = false,
)

data class DrawerListModel(
    val sections: Map<DrawerListSection, List<DrawerListItem>> = emptyMap(),
    val rows: Map<String, DrawerListRow> = emptyMap(),
    val headerFollows: Map<DrawerSectionHeaderKind, Boolean> = emptyMap(),
    val shortcuts: DrawerListShortcuts = DrawerListShortcuts(),
    val deletionError: String? = null,
    val search: String = "",
    val serverID: String = "",
    val reducedMotion: Boolean = false,
) {
    /** Every item in display order. */
    val items: List<DrawerListItem> get() = order.flatMap { sections[it].orEmpty() }

    companion object {
        val order = listOf(DrawerListSection.top, DrawerListSection.pinned, DrawerListSection.recents)

        /**
         * Home's state as the drawer shows it: shortcuts, then Pinned and Recents with their headers.
         * [timestamp] is iOS `DrawerTimestamp.text(for:now:)`; [project] resolves a row's project
         * (iOS `OpenCodeProject.resolved(for:in:)`, or `SessionListContext.project` for two-line rows).
         */
        fun build(
            state: HomeFeature.State,
            inputs: DrawerListInputs,
            liftedID: String?,
            catchUpMounted: Boolean,
            whatsNewMounted: Boolean,
            now: Instant,
            timestamp: (date: Instant, now: Instant) -> String,
            project: (OpenCodeSession, List<OpenCodeProject>) -> OpenCodeProject?,
        ): DrawerListModel {
            val status = state.statusSnapshot()
            val catchUpSessions = state.sessions.filter { status.needsCatchUp(it) }
            val catchUpCount = catchUpSessions.size
            val shortcuts = DrawerListShortcuts(
                catchUpCount = catchUpCount,
                catchUpFinish = if (state.catchUpState.justFinished && catchUpCount == 0) state.catchUpState.finishes else null,
                canMarkAllRead = catchUpSessions.any { status.indicator(it) == Indicator.unread },
                showsWhatsNew = state.showsWhatsNewButton,
                catchUpMounted = state.search.isEmpty() && catchUpMounted,
                whatsNewMounted = state.search.isEmpty() && whatsNewMounted,
            )
            val showsShortcuts = shortcuts.catchUpMounted || shortcuts.whatsNewMounted

            val top = buildList {
                if (shortcuts.catchUpMounted) add(DrawerListItem.CatchUp)
                if (shortcuts.whatsNewMounted) add(DrawerListItem.WhatsNew)
                if (state.deletionError != null) add(DrawerListItem.DeletionError)
            }

            val pinned = state.pinnedSessions
            val recents = state.recentSessions
            val rows = LinkedHashMap<String, DrawerListRow>()
            val headerFollows = HashMap<DrawerSectionHeaderKind, Boolean>()

            fun row(session: OpenCodeSession, pinned: Boolean) = DrawerListRow(
                session = session,
                isPinnedSection = pinned,
                selected = inputs.selectedSessionID == session.id,
                indicator = status.indicator(session),
                muted = status.isMuted(session.id),
                unread = status.isUnread(session),
                isLifted = liftedID == session.id,
                time = timestamp(session.updatedAt, now),
                project = if (inputs.showsProjectIcons || inputs.twoLineRows) project(session, state.projects) else null,
                showsProjectIcons = inputs.showsProjectIcons,
                server = state.server,
                reducedMotion = inputs.reducedMotion,
                increasedContrast = inputs.increasedContrast,
            )

            fun header(kind: DrawerSectionHeaderKind, follows: Boolean): List<DrawerListItem> {
                headerFollows[kind] = follows
                return if (inputs.alwaysShowsHeaders || follows) listOf(DrawerListItem.Header(kind)) else emptyList()
            }

            val pinnedItems = mutableListOf<DrawerListItem>()
            if (pinned.isNotEmpty()) {
                pinnedItems += header(DrawerSectionHeaderKind.Pinned, follows = showsShortcuts)
                for (session in pinned) {
                    rows[session.id] = row(session, pinned = true)
                    pinnedItems += DrawerListItem.Session(session.id)
                }
            }
            val recentItems = mutableListOf<DrawerListItem>()
            if (recents.isNotEmpty()) {
                recentItems += header(DrawerSectionHeaderKind.Recents, follows = pinned.isNotEmpty() || showsShortcuts)
                for (session in recents) {
                    if (session.id in rows) continue
                    rows[session.id] = row(session, pinned = false)
                    recentItems += DrawerListItem.Session(session.id)
                }
            } else if (pinned.isEmpty() && (state.search.isNotEmpty() || state.hasConnected)) {
                recentItems += DrawerListItem.Empty
            }
            return DrawerListModel(
                sections = mapOf(
                    DrawerListSection.top to top,
                    DrawerListSection.pinned to pinnedItems,
                    DrawerListSection.recents to recentItems,
                ),
                rows = rows,
                headerFollows = headerFollows,
                shortcuts = shortcuts,
                deletionError = state.deletionError,
                search = state.search,
                serverID = state.server.persistenceID,
                reducedMotion = inputs.reducedMotion,
            )
        }
    }
}
