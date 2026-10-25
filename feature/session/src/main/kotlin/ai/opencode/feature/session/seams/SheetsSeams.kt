package ai.opencode.feature.session.seams

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * SEAM: real type belongs to :feature:drawer's `InAppNotices.swift` port (iOS
 * `OpenCode/Drawer/InAppNotices.swift`). Because :feature:drawer sits above :feature:session and
 * `SessionListContext` carries it, the real type must move down (here or to :core) when the
 * drawer merges; delete this stand-in then.
 *
 * The drawer button's label: one or more other sessions that just finished or now need input.
 */
data class InAppNotice(
    /** Names this notice for its timer; a new notice gets a new one. */
    val id: Int,
    val events: List<Event>,
) {
    enum class Kind { NeedsInput, Done }

    data class Event(val sessionID: String, val title: String, val kind: Kind)

    sealed interface Target {
        data class Session(val id: String) : Target
        data object Drawer : Target
    }

    val needsInputCount: Int get() = events.count { it.kind == Kind.NeedsInput }
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

    /** What TalkBack hears: the label, without moving focus. */
    val announcement: String get() = "$title, $status"
}
