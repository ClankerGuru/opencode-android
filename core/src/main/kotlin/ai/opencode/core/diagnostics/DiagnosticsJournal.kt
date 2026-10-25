package ai.opencode.core.diagnostics

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Port of iOS `DiagnosticsJournal` (`OpenCode/Diagnostics/DiagnosticsJournal.swift`): the
 * diagnostics timeline on disk, an append-only JSON-lines file per run that rotates into one older
 * segment, so a run keeps at most two segments. Each write reaches the file before the next event,
 * so the tail survives a crash or a kill. At launch the previous run's segments are set aside, and
 * a marker written on activation and cleared on backgrounding tells whether that run ended while
 * it was in the foreground.
 *
 * Android: the marker follows `ProcessLifecycleOwner` (ON_RESUME ↔ iOS didBecomeActive, ON_STOP ↔
 * didEnterBackground); Android has no reliable willTerminate, and a process killed in the
 * background never holds the marker, so only a foreground crash, ANR kill or debugger stop leaves
 * it. Files live in `noBackupFilesDir/Diagnostics` (never backed up), as iOS keeps them out of
 * iCloud backups in Application Support.
 */
class DiagnosticsJournal(
    val directory: File,
    val segmentBytes: Int = 768 * 1_024,
    /** "version (build)", written into the foreground marker. */
    val build: String,
) {
    data class PreviousRun(
        /** The run ended in the foreground without backgrounding first: a crash, an ANR kill, or a debugger stop. */
        val endedUnexpectedly: Boolean,
    )

    private val queue: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "opencode.diagnostics.journal").apply { isDaemon = true } }

    // Confined to `queue`.
    private var stream: FileOutputStream? = null
    private var bytes = 0L
    private var isRecording = false

    private val current get() = File(directory, "current.jsonl")
    private val older get() = File(directory, "current.1.jsonl")
    private val previous get() = listOf(File(directory, "previous.1.jsonl"), File(directory, "previous.jsonl"))
    private val marker get() = File(directory, "foreground")

    private fun <T> sync(block: () -> T): T = queue.submit(Callable { block() }).get()

    private fun async(block: () -> Unit) {
        queue.execute(block)
    }

    /** Sets the previous run aside and starts this one. Call once, before appending. */
    fun open(recording: Boolean): PreviousRun = sync {
        directory.mkdirs()
        val markedBuild = runCatching { marker.readText() }.getOrNull()
        marker.delete()
        val segments = listOf(older, current)
        val hadEvents = segments.any { it.exists() }
        if (hadEvents) {
            for ((source, destination) in segments.zip(previous)) {
                destination.delete()
                source.renameTo(destination)
            }
        }
        isRecording = recording
        PreviousRun(endedUnexpectedly = markedBuild == build && hadEvents)
    }

    fun append(event: DiagnosticsEvent) = async {
        isRecording = true
        val line = runCatching { (json.encodeToString(DiagnosticsEvent.serializer(), event) + "\n").toByteArray() }.getOrNull() ?: return@async
        if (bytes + line.size > segmentBytes && stream != null) rotate()
        val out = stream ?: openCurrent() ?: return@async
        try {
            out.write(line)
            out.flush()
            bytes += line.size
        } catch (_: Exception) {
            runCatching { out.close() }
            stream = null
        }
    }

    /** Forgets this run and the previous one. */
    fun clear() = async {
        runCatching { stream?.close() }
        stream = null
        bytes = 0
        isRecording = false
        (listOf(current, older) + previous).forEach { it.delete() }
    }

    /** Rewrites every segment without voice excerpts: Include Transcripts was turned off. */
    fun removeExcerpts() = async {
        runCatching { stream?.close() }
        stream = null
        for (file in listOf(older, current) + previous) {
            val events = events(file)
            if (events.none { it.excerpt != null }) continue
            val text = buildString {
                for (event in events) {
                    val line = runCatching { json.encodeToString(DiagnosticsEvent.serializer(), event.copy(excerpt = null)) }.getOrNull() ?: continue
                    append(line).append('\n')
                }
            }
            val temporary = File(directory, file.name + ".tmp")
            runCatching {
                temporary.writeText(text)
                temporary.renameTo(file)
            }
        }
        bytes = 0
    }

    /** The previous run's events, oldest first. Reads up to two segments, so call it when building a report, not at launch. */
    fun previousRunEvents(): List<DiagnosticsEvent> = sync { previous.flatMap(::events) }

    /** This run's events as written, oldest first. */
    fun currentRunEvents(): List<DiagnosticsEvent> = sync {
        runCatching { stream?.fd?.sync() }
        listOf(older, current).flatMap(::events)
    }

    /** Waits for queued writes. */
    fun flush() = sync {}

    fun markForeground() = async {
        if (!isRecording) return@async
        runCatching {
            directory.mkdirs()
            marker.writeText(build)
        }
    }

    fun markBackground() = async { marker.delete() }

    /** Follows the app's foreground state for the unclean-exit marker. */
    fun followApplicationState() {
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) = markForeground()
                override fun onStop(owner: LifecycleOwner) = markBackground()
            })
        }
    }

    private fun rotate() {
        runCatching { stream?.close() }
        stream = null
        bytes = 0
        older.delete()
        current.renameTo(older)
    }

    private fun openCurrent(): FileOutputStream? {
        directory.mkdirs()
        val opened = runCatching { FileOutputStream(current, true) }.getOrNull() ?: return null
        bytes = current.length()
        stream = opened
        return opened
    }

    companion object {
        private val json = Json { encodeDefaults = false; explicitNulls = false; ignoreUnknownKeys = true }

        /** `noBackupFilesDir/Diagnostics`, following the app's foreground state. */
        fun live(context: Context): DiagnosticsJournal {
            val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            val journal = DiagnosticsJournal(
                directory = File(context.noBackupFilesDir, "Diagnostics"),
                build = "${info?.versionName ?: "?"} (${info?.longVersionCode ?: "?"})",
            )
            journal.followApplicationState()
            return journal
        }

        /** A torn last line from a crash is skipped, not fatal. */
        private fun events(file: File): List<DiagnosticsEvent> {
            if (!file.exists()) return emptyList()
            return runCatching { file.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
                if (line.isBlank()) null else runCatching { json.decodeFromString(DiagnosticsEvent.serializer(), line) }.getOrNull()
            }
        }
    }
}
