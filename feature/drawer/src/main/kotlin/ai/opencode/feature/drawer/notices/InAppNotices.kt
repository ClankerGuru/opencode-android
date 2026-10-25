package ai.opencode.feature.drawer.notices

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.experiments.InAppNoticeMode
import ai.opencode.core.sharing.shared
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Port of iOS `OpenCode/Drawer/InAppNotices.swift`, the model and rules half: the mode, the
// notice and its timing and copy, the signal Home derives from its live state, and the clients
// that clear delivered notifications and speak to TalkBack. The drawer button's label (the view)
// is `DrawerNoticeLabel` in `motion/DrawerButton.kt`, which reads only [InAppNotice] (its [InAppNotice.title],
// [InAppNotice.status], [InAppNotice.needsInput], [InAppNotice.target]) and sends Home's
// `inAppNoticeTapped`; Home's wiring (queueing, the timer, the haptic and the announcement) is in
// `HomeFeatureNotifications.kt`.

/**
 * Whether the mode shows a notice of this kind ([InAppNoticeMode] itself moved down to `:core`,
 * `ai.opencode.core.experiments`, as Settings › Notifications writes it).
 */
fun InAppNoticeMode.shows(kind: InAppNotice.Kind): Boolean = when (this) {
    InAppNoticeMode.off -> false
    InAppNoticeMode.needsInput -> kind == InAppNotice.Kind.needsInput
    InAppNoticeMode.all -> true
}

/**
 * The mode as reducers and the notification handler read it (iOS `@Dependency(\.inAppNoticeSettings)`).
 * iOS lab runs could force one with `OPENCODE_LAB_IN_APP_NOTICES`; Android has no launch
 * environment, so the saved choice decides.
 */
class InAppNoticeSettings(val mode: () -> InAppNoticeMode) {
    companion object Key : DependencyKey<InAppNoticeSettings> {
        override val liveValue = InAppNoticeSettings { shared(InAppNoticeMode.key).value }
        override val testValue = InAppNoticeSettings { InAppNoticeMode.off }

        fun constant(mode: InAppNoticeMode) = InAppNoticeSettings { mode }
    }
}

/**
 * The drawer button's label: one or more other sessions that just finished or now need input,
 * shown for a few seconds before it folds back into the button's dot. One at a time; a later
 * event updates it in place.
 */
data class InAppNotice(
    /** Names this notice for its timer; a new notice gets a new one. */
    val id: Int,
    val events: List<Event>,
) {
    @Suppress("EnumEntryName")
    enum class Kind { needsInput, done }

    data class Event(val sessionID: String, val title: String, val kind: Kind)

    sealed interface Target {
        data class Session(val sessionID: String) : Target
        data object Drawer : Target
    }

    val needsInputCount: Int get() = events.count { it.kind == Kind.needsInput }
    val needsInput: Boolean get() = needsInputCount > 0

    /** Needs your input holds 6 s, Done 4 s; several hold the longer. */
    val hold: Duration get() = if (needsInput || events.size > 1) 6.seconds else 4.seconds

    val target: Target get() = if (events.size == 1) Target.Session(events[0].sessionID) else Target.Drawer

    val title: String get() = if (events.size == 1) events[0].title else "${events.size} sessions"

    val status: String
        get() {
            val waiting = needsInputCount
            if (events.size <= 1) return if (waiting > 0) "Needs your input" else "Done"
            if (waiting <= 0) return "Done"
            return "$waiting ${if (waiting == 1) "needs" else "need"} your input"
        }

    /** The notice after [merge], and the events that were new to it. */
    data class Merged(val notice: InAppNotice, val added: List<Event>)

    /**
     * Adds events, a session's newer one replacing its older, and Needs your input outranking
     * Done for the same session. Returns the merged notice and the events new to it.
     */
    fun merge(incoming: List<Event>): Merged {
        val events = events.toMutableList()
        val added = mutableListOf<Event>()
        for (event in incoming) {
            val index = events.indexOfFirst { it.sessionID == event.sessionID }
            if (index >= 0) {
                if (!(events[index].kind == Kind.done && event.kind == Kind.needsInput)) {
                    events[index] = events[index].copy(title = event.title)
                    continue
                }
                events[index] = event
                added += event
            } else {
                events += event
                added += event
            }
        }
        return Merged(copy(events = events), added)
    }

    /** What TalkBack hears: the label, without moving focus. */
    val announcement: String get() = "$title, $status"
}

/**
 * What the notice rules read about each listed root session, derived from Home's live state (the
 * event stream, the attention read, read receipts), never from a push.
 */
data class InAppNoticeSignal(
    /** Only changes between two live readings count: a reconnect or a cold launch's first list isn't news. */
    val isLive: Boolean,
    val sessions: Map<String, Session>,
) {
    data class Session(
        val title: String,
        val needsInput: Boolean,
        val unread: Boolean,
        val idle: Long?,
    )

    /** An event and the key that remembers it was noticed. */
    data class KeyedEvent(val event: InAppNotice.Event, val key: String)

    /** Whether a shown event still holds: answered or read anywhere, it goes. */
    fun stillHolds(event: InAppNotice.Event): Boolean {
        val session = sessions[event.sessionID] ?: return false
        return if (event.kind == InAppNotice.Kind.needsInput) session.needsInput else session.unread && !session.needsInput
    }

    companion object {
        val none = InAppNoticeSignal(isLive = false, sessions = emptyMap())

        /**
         * The events between two readings. The caller filters out the session on screen, the
         * mode, and anything already noticed.
         */
        fun events(from: InAppNoticeSignal, to: InAppNoticeSignal): List<KeyedEvent> {
            if (!from.isLive || !to.isLive) return emptyList()
            val events = mutableListOf<KeyedEvent>()
            for ((id, session) in to.sessions.entries.sortedBy { it.key }) {
                val before = from.sessions[id]
                if (before == null) {
                    // A session that appears already asking is still asking.
                    if (session.needsInput) {
                        events += KeyedEvent(InAppNotice.Event(id, session.title, InAppNotice.Kind.needsInput), "input|$id")
                    }
                    continue
                }
                val idle = session.idle
                if (session.needsInput && !before.needsInput) {
                    events += KeyedEvent(InAppNotice.Event(id, session.title, InAppNotice.Kind.needsInput), "input|$id")
                } else if (!session.needsInput && session.unread && idle != null && (before.idle?.let { idle > it } ?: true)) {
                    // A newer idle watermark is a run that finished; unread means no client has
                    // read it since.
                    events += KeyedEvent(InAppNotice.Event(id, session.title, InAppNotice.Kind.done), "done|$id|$idle")
                }
            }
            return events
        }
    }
}

/** Removes delivered notifications from the notification shade. */
class DeliveredNotificationsClient(
    /** Every notification for these sessions (their thread identifiers). */
    val removeAll: suspend (sessionIDs: Set<String>) -> Unit,
    /** Only their needs-input notifications, once the request is answered. */
    val removeNeedsInput: suspend (sessionIDs: Set<String>) -> Unit,
) {
    companion object Key : DependencyKey<DeliveredNotificationsClient> {
        override val liveValue: DeliveredNotificationsClient by lazy {
            val context = dependency(AppContext)
            DeliveredNotificationsClient(
                removeAll = { DeliveredNotifications.remove(context, it, needsInputOnly = false) },
                removeNeedsInput = { DeliveredNotifications.remove(context, it, needsInputOnly = true) },
            )
        }
        override val testValue = DeliveredNotificationsClient(removeAll = {}, removeNeedsInput = {})
    }
}

/**
 * How the app's session notifications are recognised and shown.
 *
 * The Android form of iOS's `userInfo["opencode"]` payload and `threadIdentifier` (agreed with the
 * notifications port in `:feature:system`): a posted session notification carries
 * [EXTRA_SESSION_ID] and [EXTRA_STATUS] in its extras and the session ID as its group.
 */
object DeliveredNotifications {
    /** iOS `opencode.sessionID`. */
    const val EXTRA_SESSION_ID = "opencode.sessionID"

    /** iOS `opencode.status`. */
    const val EXTRA_STATUS = "opencode.status"

    /** Statuses a needs-input notification carries in [EXTRA_STATUS]. */
    val needsInputStatuses: Set<String> = setOf("needs-input", "permission", "question", "form")

    fun sessionID(notification: StatusBarNotification): String? {
        notification.notification.extras?.getString(EXTRA_SESSION_ID)?.let { return it }
        return notification.notification.group?.takeIf { it.isNotEmpty() }
    }

    fun isNeedsInput(notification: StatusBarNotification): Boolean {
        val status = notification.notification.extras?.getString(EXTRA_STATUS) ?: return false
        return status in needsInputStatuses
    }

    suspend fun remove(context: Context, sessionIDs: Set<String>, needsInputOnly: Boolean) {
        if (sessionIDs.isEmpty()) return
        withContext(Dispatchers.Default) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return@withContext
            val matches = manager.activeNotifications.filter { notification ->
                val id = sessionID(notification) ?: return@filter false
                id in sessionIDs && (!needsInputOnly || isNeedsInput(notification))
            }
            for (notification in matches) manager.cancel(notification.tag, notification.id)
        }
    }

    /** iOS `UNNotificationPresentationOptions`' members the app uses. */
    @Suppress("EnumEntryName")
    enum class Presentation { banner, sound, list }

    /**
     * How a notification arriving in the foreground shows. The session on screen shows nothing;
     * while In-App Notices is on, the drawer button tells of the others, so they go only to the
     * shade, silently.
     */
    fun presentation(sessionID: String?, visibleSessionID: String?, mode: InAppNoticeMode): Set<Presentation> {
        val everything = setOf(Presentation.banner, Presentation.sound, Presentation.list)
        if (sessionID == null) return everything
        if (sessionID == visibleSessionID) return emptySet()
        return if (mode == InAppNoticeMode.off) everything else setOf(Presentation.list)
    }
}

/** TalkBack announcements that don't move focus (iOS `AccessibilityAnnouncer`). Main thread. */
class AccessibilityAnnouncer(val announce: (text: String, urgent: Boolean) -> Unit) {
    companion object Key : DependencyKey<AccessibilityAnnouncer> {
        override val liveValue: AccessibilityAnnouncer by lazy {
            val context = dependency(AppContext)
            AccessibilityAnnouncer { text, _ ->
                // Android has no announcement priority; an urgent one reads the same.
                val manager = context.getSystemService(AccessibilityManager::class.java)
                if (manager == null || !manager.isEnabled) return@AccessibilityAnnouncer
                @Suppress("DEPRECATION")
                val event = AccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
                    packageName = context.packageName
                    this.text.add(text)
                }
                manager.sendAccessibilityEvent(event)
            }
        }
        override val testValue = AccessibilityAnnouncer { _, _ -> }
    }
}
