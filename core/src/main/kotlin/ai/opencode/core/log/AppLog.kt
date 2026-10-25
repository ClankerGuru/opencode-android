package ai.opencode.core.log

import android.os.Trace
import android.util.Log
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration

/**
 * Port of iOS `AppLog` (`OpenCode/Core/AppLog.swift`): the app's only log. Lines go to
 * logcat under tag `OpenCode/<category>` and into an in-process ring buffer that
 * [recentLines] reads (iOS reads `OSLogStore`) for diagnostics/feedback reports.
 *
 * PRIVACY (same rule as iOS): log IDs, counts, lengths, statuses and timings only. Never
 * tokens, credentials, server addresses, pairing codes, message text, or file contents.
 * Use the `logSummary` helpers for errors and durations. Never call `android.util.Log`
 * directly anywhere else.
 *
 * Messages are lambdas, built only when the level is collected (iOS's autoclosure).
 */
class AppLog private constructor(val category: String, subsystem: String = SUBSYSTEM, private val traces: Boolean = false) {
    private val tag = if (subsystem == SUBSYSTEM) "OpenCode/$category" else "$subsystem/$category"

    fun debug(message: () -> String) = log(Log.DEBUG, "debug", message)
    fun info(message: () -> String) = log(Log.INFO, "info", message)
    fun notice(message: () -> String) = log(Log.INFO, "notice", message)
    fun error(message: () -> String) = log(Log.ERROR, "error", message)

    fun debug(message: String) = debug { message }
    fun info(message: String) = info { message }
    fun notice(message: String) = notice { message }
    fun error(message: String) = error { message }

    private inline fun log(priority: Int, level: String, message: () -> String) {
        if (priority < minimumPriority) return
        val text = message().replace('\r', ' ').replace('\n', ' ')
        Log.println(priority, tag, text)
        // `opencode.perf` lines double as Perfetto instants named by their first word (iOS signposts).
        if (traces && Trace.isEnabled()) {
            Trace.beginSection("oc:" + text.substringBefore(' ').take(117))
            Trace.endSection()
        }
        Ring.append("${DateTimeFormatter.ISO_INSTANT.format(Instant.now())} $level $category: $text")
    }

    companion object {
        const val SUBSYSTEM = "ai.opencode.mobilecode"

        /** Lowest priority collected; debug builds set [Log.DEBUG] at startup. */
        @Volatile
        var minimumPriority: Int = Log.INFO

        val send = AppLog("send")
        val network = AppLog("network")
        val stream = AppLog("stream")
        val voice = AppLog("voice")
        val app = AppLog("app")

        /** The responsiveness gauntlet's timing marks (`LabMarks`), under the `opencode.perf` subsystem. */
        val perf = AppLog("session", subsystem = "opencode.perf", traces = true)

        /** Recent `AppLog` lines from this process, oldest first. */
        fun recentLines(since: Instant): List<String> = Ring.since(since)
    }

    private object Ring {
        private const val CAPACITY = 4_000
        private val lines = ArrayDeque<Pair<Instant, String>>()

        fun append(line: String) = synchronized(lines) {
            if (lines.size == CAPACITY) lines.removeFirst()
            lines.addLast(Instant.now() to line)
        }

        fun since(date: Instant): List<String> = synchronized(lines) {
            lines.filter { !it.first.isBefore(date) }.map { it.second }
        }
    }
}

/** "1.25 s", "320 ms" (iOS `Duration.logSummary`). */
val Duration.logSummary: String
    get() {
        val milliseconds = inWholeNanoseconds / 1_000_000.0
        if (milliseconds < 1_000) return "${Math.round(milliseconds)} ms"
        val seconds = milliseconds / 1_000
        val text = String.format(Locale.ROOT, "%.2f", seconds).trimEnd('0').trimEnd('.')
        return "$text s"
    }
