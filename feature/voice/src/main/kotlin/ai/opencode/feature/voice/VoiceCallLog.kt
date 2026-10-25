package ai.opencode.feature.voice

import ai.opencode.core.dependencies.*
import ai.opencode.core.sharing.InstantSerializer
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import android.content.Context
import android.net.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.max

object VoiceLogUUIDSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("VoiceLogUUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder) = UUID.fromString(decoder.decodeString())
}

/** Four call timeline tracks plus notes. Raw speech stays in private app storage;
 * only an explicit snapshot attachment is redacted and bounded. Never AppLog it. */
@Serializable
data class VoiceCallLog(
    @Serializable(with = VoiceLogUUIDSerializer::class) val id: UUID,
    @Serializable(with = InstantSerializer::class) val startedAt: Instant,
    @Serializable(with = InstantSerializer::class) val endedAt: Instant? = null,
    val title: String, val engine: String,
    val spans: List<Span> = emptyList(), val notes: List<Note> = emptyList(),
) {
    @Serializable enum class Track { user, voice, delegator, tool }
    @Serializable data class Span(val id: String, val track: Track, val start: Double, val end: Double? = null,
        val label: String, val detail: String? = null, val failed: Boolean = false)
    @Serializable data class Note(val id: Int, val t: Double, val kind: Kind, val text: String) {
        @Serializable enum class Kind { marker, received, injected, audio }
    }
    val duration get() = maxOf(endedAt?.let { seconds(startedAt, it) } ?: 0.0,
        spans.maxOfOrNull { it.end ?: it.start } ?: 0.0, notes.maxOfOrNull { it.t } ?: 0.0)
    fun trimmed(at: Instant = dependency(DateClient).now(), window: Double = 600.0, maxFieldBytes: Int = 4000): VoiceCallLog {
        val nowT = max(duration, seconds(startedAt, at)); val cutoff = max(0.0, nowT - window)
        return copy(spans = spans.filter { (it.end ?: nowT) >= cutoff }.map { it.copy(label = capUTF8(it.label, maxFieldBytes), detail = it.detail?.let { d -> capUTF8(d, maxFieldBytes) }) },
            notes = notes.filter { it.t >= cutoff }.map { it.copy(text = capUTF8(it.text, maxFieldBytes)) })
    }
    fun redacted() = copy(title = redactText(title), spans = spans.map { it.copy(label = redactText(it.label), detail = it.detail?.let(::redactText)) },
        notes = notes.map { it.copy(text = redactText(it.text)) })
    fun boundedJSON(maxBytes: Int = 512000): ByteArray {
        var current = this; var data = json.encodeToString(current).toByteArray()
        while (data.size > maxBytes && (current.spans.isNotEmpty() || current.notes.isNotEmpty())) {
            current = current.copy(spans = current.spans.drop(max(1, current.spans.size / 4)), notes = current.notes.drop(max(1, current.notes.size / 4)))
            data = json.encodeToString(current).toByteArray()
        }
        return data
    }
    companion object {
        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        internal fun seconds(start: Instant, end: Instant) = java.time.Duration.between(start, end).toNanos() / 1e9
        private fun capUTF8(text: String, maxBytes: Int): String {
            val bytes = text.toByteArray()
            return if (bytes.size <= maxBytes) text else bytes.copyOf(maxBytes.coerceAtLeast(0)).toString(Charsets.UTF_8) + "…"
        }
        private val redactionRules = listOf(
            Regex("sk-[A-Za-z0-9_-]{8,}") to "sk-<redacted>",
            Regex("(?i)(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+") to "$1 <redacted>",
            Regex("\\b[0-9]{10,}\\.[A-Za-z0-9_-]{20,}\\b") to "<redacted-token>",
            Regex("((?:https?|wss?)://)[^/\\s?#@\"'`]+@") to "$1<redacted>@",
            Regex("(/auth/connect/)[^/\\s?#@\"'`]+") to "$1<redacted>",
            Regex("(?i)([?&](?:token|key|api_key|password)=)[^&\\s#\"'`]+") to "$1<redacted>",
        )
        fun redactText(text: String) = redactionRules.fold(text) { result, (regex, replacement) -> regex.replace(result, replacement) }
    }
}

/** :app/devtools installs upload when Developer Mode is enabled; no upward edge. */
class VoiceCallLogUploadClient(val upload: suspend (VoiceCallLog) -> Unit = {}) {
    /** :app passes `this[VoiceCallLogUploadClient] = VoiceCallLogUploadClient { log -> … }`
     * to DependencyValues.installRoot before resolving VoiceCallLogClient. The suspend
     * callback uploads the completed log through Developer Mode's diagnostics uploader,
     * honoring its transcript/privacy settings. The recorder gates automatic uploads
     * on Developer Mode; the adapter must not put log contents or upload URLs in AppLog. */
    companion object Key : DependencyKey<VoiceCallLogUploadClient> {
        override val liveValue = VoiceCallLogUploadClient()
        override val testValue = VoiceCallLogUploadClient()
    }
}

class VoiceCallLogRecorder(
    private val directory: File? = null,
    private val context: Context? = null,
    private val date: DateClient = dependency(DateClient),
    private val upload: VoiceCallLogUploadClient = dependency(VoiceCallLogUploadClient),
) {
    private data class Open(var log: VoiceCallLog, var nextNote: Int = 0, var openVoiceSpan: String? = null)
    private val lock = Any()
    private val diskLock = Any()
    private val open = linkedMapOf<UUID, Open>()
    private val networkWatches = mutableMapOf<UUID, ConnectivityManager.NetworkCallback>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saving = mutableMapOf<UUID, VoiceCallLog>()
    fun begin(id: UUID, title: String, engine: String, at: Instant = date.now()) {
        synchronized(lock) { open[id] = Open(VoiceCallLog(id, at, title = title, engine = engine)) }
        watchNetwork(id)
    }
    private fun watchNetwork(id: UUID) {
        val manager = context?.getSystemService(ConnectivityManager::class.java) ?: return
        stopWatchingNetwork(id)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var last: String? = null
            private fun update(text: String) { if (text != last) { last = text; note(id, VoiceCallLog.Note.Kind.audio, "Network: $text") } }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { update(describe(capabilities, manager.isActiveNetworkMetered, manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)) }
            override fun onLost(network: Network) { update("no connection") }
            override fun onUnavailable() { update("no connection") }
        }
        synchronized(lock) { networkWatches[id] = callback }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }
    private fun stopWatchingNetwork(id: UUID) {
        val callback = synchronized(lock) { networkWatches.remove(id) } ?: return
        runCatching { context?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }
    fun span(id: UUID, track: VoiceCallLog.Track, spanID: String, label: String? = null, detail: String? = null,
        end: Boolean = false, failed: Boolean = false, at: Instant = date.now()) = synchronized(lock) {
        val call = open[id] ?: return@synchronized
        val t = VoiceCallLog.seconds(call.log.startedAt, at); val spans = call.log.spans.toMutableList(); val index = spans.indexOfFirst { it.id == spanID }
        if (index >= 0) {
            val old = spans[index]; spans[index] = old.copy(label = label ?: old.label, detail = detail ?: old.detail,
                end = if (end) max(t, old.start) else old.end, failed = failed || old.failed)
        } else spans.add(VoiceCallLog.Span(spanID, track, t, if (end) t else null, label ?: "", detail, failed))
        if (track == VoiceCallLog.Track.voice) call.openVoiceSpan = if (end) null else spanID
        call.log = call.log.copy(spans = spans)
    }
    fun endVoice(id: UUID, label: String?, at: Instant = date.now()) {
        val spanID = synchronized(lock) { open[id]?.openVoiceSpan } ?: return
        span(id, VoiceCallLog.Track.voice, spanID, label, end = true, at = at)
    }
    fun note(id: UUID, kind: VoiceCallLog.Note.Kind, text: String, at: Instant = date.now()) = synchronized(lock) {
        val call = open[id] ?: return@synchronized
        call.log = call.log.copy(notes = call.log.notes + VoiceCallLog.Note(call.nextNote++, VoiceCallLog.seconds(call.log.startedAt, at), kind, text))
    }
    fun noteCurrent(kind: VoiceCallLog.Note.Kind, text: String, at: Instant = date.now()) {
        val id = synchronized(lock) { open.keys.firstOrNull() } ?: return
        note(id, kind, text, at)
    }
    fun setTitle(id: UUID, title: String) = synchronized(lock) { open[id]?.let { it.log = it.log.copy(title = title) }; Unit }
    fun setEngine(id: UUID, engine: String) = synchronized(lock) { open[id]?.let { it.log = it.log.copy(engine = engine) }; Unit }
    fun snapshot(id: UUID, at: Instant = date.now(), window: Double = 600.0): VoiceCallLog? {
        val raw = synchronized(lock) { open[id]?.log ?: saving[id] } ?: list().firstOrNull { it.id == id }
        return raw?.trimmed(at, window)?.redacted()
    }
    fun finish(id: UUID, at: Instant = date.now()) {
        stopWatchingNetwork(id)
        val original = synchronized(lock) { open.remove(id)?.log } ?: return
        val t = VoiceCallLog.seconds(original.startedAt, at)
        val log = original.copy(endedAt = at, spans = original.spans.map { if (it.end == null) it.copy(end = max(t, it.start)) else it })
        if (log.spans.isEmpty() && log.notes.isEmpty()) return
        synchronized(lock) { saving[id] = log }
        val shouldUpload = directory != null && shared(SharedKeys.developerMode).value
        scope.launch {
            try { save(log); if (shouldUpload) upload.upload(log) }
            finally { synchronized(lock) { saving.remove(id) } }
        }
    }
    private fun save(log: VoiceCallLog) = synchronized(diskLock) {
        val dir = directory ?: return@synchronized
        runCatching {
            dir.mkdirs()
            val file = File(dir, "${log.id}.json"); val temp = File(dir, "${log.id}.pending")
            temp.writeText(VoiceCallLog.json.encodeToString(log)); check(temp.renameTo(file))
            for (old in savedLogs().drop(keep)) delete(old.id)
        }
    }
    /** Disk I/O: callers must invoke off the main thread (the reducer uses an effect). */
    /** Saved logs, including one whose save is still writing (iOS saves before `finish` returns). */
    fun list(): List<VoiceCallLog> {
        if (directory == null) return emptyList()
        val pending = synchronized(lock) { saving.values.toList() }
        val saved = savedLogs()
        return (pending.filter { log -> saved.none { it.id == log.id } } + saved).sortedByDescending { it.startedAt }
    }
    private fun savedLogs(): List<VoiceCallLog> = synchronized(diskLock) {
        directory?.listFiles()?.filter { it.extension == "json" }?.mapNotNull { runCatching { VoiceCallLog.json.decodeFromString<VoiceCallLog>(it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.startedAt } ?: emptyList()
    }
    fun delete(id: UUID) { synchronized(diskLock) { directory?.let { File(it, "$id.json").delete() } } }
    fun deleteAll() { list().forEach { delete(it.id) } }
    companion object {
        const val keep = 40
        fun describe(capabilities: NetworkCapabilities?, expensive: Boolean = false, constrained: Boolean = false): String {
            if (capabilities == null) return "no connection"
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "waiting for a connection"
            val kind = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wired"
                else -> "other"
            }
            return listOfNotNull(kind, if (expensive) "expensive" else null, if (constrained) "Low Data Mode" else null).joinToString(" · ")
        }
    }
}
class VoiceCallLogClient(val recorder: VoiceCallLogRecorder? = null) {
    fun noteCurrent(kind: VoiceCallLog.Note.Kind, text: String) { recorder?.noteCurrent(kind, text) }
    fun noteCurrent(kind: VoiceCallLog.Note.Kind, text: String, at: Instant) { recorder?.noteCurrent(kind, text, at) }
    fun list() = recorder?.list() ?: emptyList()
    fun delete(id: UUID) { recorder?.delete(id) }
    fun deleteAll() { recorder?.deleteAll() }
    companion object Key : DependencyKey<VoiceCallLogClient> {
        override val liveValue by lazy { val context = dependency(AppContext); VoiceCallLogClient(VoiceCallLogRecorder(File(context.noBackupFilesDir, "VoiceCallLogs"), context)) }
        override val testValue = VoiceCallLogClient()
    }
}
