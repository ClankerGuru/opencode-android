package ai.opencode.feature.session

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Port of the notice model of iOS `OpenCode/Drawer/InAppNotices.swift`. It lives here, below
// `:feature:drawer`, because `SessionListContext` carries it to the session's header; the rules,
// the mode and Home's wiring stay in the drawer (`notices/InAppNotices.kt`,
// `HomeFeatureNotifications.kt`).

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

