package ai.opencode.feature.session.seams

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * SEAM: real type belongs to :feature:system (iOS `OpenCode/System/Notifications/SessionNotifications.swift`);
 * delete when that area merges.
 *
 * The callback URL a send attaches so the server's notify plugin can tell this phone when the
 * run ends. Only the members the Session sheet uses.
 */
class SessionNotificationsClient(
    /** Whether this build has a relay and a push environment to offer at all. */
    val isAvailable: Boolean,
    /** Null means notifications are off (or unavailable). */
    val callbackURL: suspend (OpenCodeServer) -> String?,
) {
    companion object Key : DependencyKey<SessionNotificationsClient> {
        // No relay is wired on Android yet: Always Notify stays hidden, as on an iOS build without one.
        override val liveValue = SessionNotificationsClient(isAvailable = false, callbackURL = { null })
        override val testValue = SessionNotificationsClient(isAvailable = true, callbackURL = { null })
    }
}

/**
 * SEAM: real type belongs to :feature:transcript (iOS `OpenCode/Transcript/TranscriptEntry.swift`);
 * delete when that area merges.
 */
object TranscriptEntry {
    /** The prompt (user message) an item belongs to, for user-role rows only. */
    fun promptID(item: OpenCodeTranscriptItem): String? = when (item) {
        is OpenCodeTranscriptItem.UserText -> item.id
        is OpenCodeTranscriptItem.UserImages -> if (item.id.endsWith("-images")) item.id.dropLast("-images".length) else item.id
        is OpenCodeTranscriptItem.UserFiles -> if (item.id.endsWith("-files")) item.id.dropLast("-files".length) else item.id
        else -> null
    }
}

/**
 * SEAM: real type belongs to :feature:transcript (iOS `OpenCode/Transcript/OpenCodeMarkdownBlock.swift`);
 * delete when that area merges.
 *
 * Only what Since You Left reads: each block's kind and, for prose, its plain text. [blocks] is a
 * small line-based stand-in for the transcript's Markdown parser (fences, headings, lists,
 * quotes, tables, rules, images); inline marks are stripped to plain text.
 */
data class OpenCodeMarkdownBlock(val id: Int, val kind: Kind) {
    sealed interface Kind {
        data class Paragraph(val text: String) : Kind
        data class NumberedItem(val marker: String, val text: String) : Kind
        data class ListItem(val marker: String, val level: Int, val text: String) : Kind
        data class Blockquote(val text: String) : Kind
        data class Heading(val level: Int, val text: String) : Kind
        data class Code(val source: String, val language: String?) : Kind
        data object Table : Kind
        data class Image(val alt: String, val source: String) : Kind
        data object Divider : Kind
        data object Other : Kind
    }

    companion object {
        private val numbered = Regex("^(\\d+[.)])\\s+(.*)$")
        private val bullet = Regex("^([-*+])\\s+(.*)$")
        private val heading = Regex("^(#{1,6})\\s+(.*)$")
        private val image = Regex("^!\\[([^]]*)]\\(([^)]*)\\)\\s*$")
        private val divider = Regex("^(\\*{3,}|-{3,}|_{3,})\\s*$")

        fun blocks(markdown: String): List<OpenCodeMarkdownBlock> {
            val kinds = mutableListOf<Kind>()
            val paragraph = mutableListOf<String>()
            fun flush() {
                if (paragraph.isNotEmpty()) kinds += Kind.Paragraph(inline(paragraph.joinToString(" ")))
                paragraph.clear()
            }
            val lines = markdown.replace("\r\n", "\n").split('\n')
            var index = 0
            while (index < lines.size) {
                val raw = lines[index]
                val line = raw.trim()
                when {
                    line.startsWith("```") || line.startsWith("~~~") -> {
                        flush()
                        val fence = line.take(3)
                        val language = line.drop(3).trim().ifEmpty { null }
                        val code = mutableListOf<String>()
                        index++
                        while (index < lines.size && !lines[index].trim().startsWith(fence)) code += lines[index++]
                        kinds += Kind.Code(code.joinToString("\n"), language)
                    }
                    line.isEmpty() -> flush()
                    divider.matches(line) -> { flush(); kinds += Kind.Divider }
                    heading.matches(line) -> {
                        flush()
                        val match = heading.find(line)!!
                        kinds += Kind.Heading(match.groupValues[1].length, inline(match.groupValues[2]))
                    }
                    image.matches(line) -> {
                        flush()
                        val match = image.find(line)!!
                        kinds += Kind.Image(match.groupValues[1], match.groupValues[2])
                    }
                    line.startsWith("|") -> {
                        flush()
                        while (index + 1 < lines.size && lines[index + 1].trim().startsWith("|")) index++
                        kinds += Kind.Table
                    }
                    line.startsWith(">") -> { flush(); kinds += Kind.Blockquote(inline(line.trimStart('>').trim())) }
                    bullet.matches(line) -> {
                        flush()
                        val match = bullet.find(line)!!
                        val level = (raw.length - raw.trimStart().length) / 2
                        kinds += Kind.ListItem("•", level, inline(match.groupValues[2]))
                    }
                    numbered.matches(line) -> {
                        flush()
                        val match = numbered.find(line)!!
                        kinds += Kind.NumberedItem(match.groupValues[1], inline(match.groupValues[2]))
                    }
                    else -> paragraph += line
                }
                index++
            }
            flush()
            return kinds.mapIndexed { id, kind -> OpenCodeMarkdownBlock(id, kind) }
        }

        private val link = Regex("\\[([^]]*)]\\([^)]*\\)")
        private val marks = Regex("(\\*\\*|__|\\*|`|~~)")

        private fun inline(text: String): String = text.replace(link, "$1").replace(marks, "")
    }
}

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
