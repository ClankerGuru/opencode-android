package ai.opencode.feature.voice

import ai.opencode.core.dependencies.*
import ai.opencode.core.log.AppLog
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/** Call-owned notification/Live Activity command channel. A command carries its
 * call identity: old PendingIntents can never mute or end a newer call. The reducer
 * collects events while active and handles muteTapped/endTapped, not endPressed. */
class VoiceCallCommands {
    enum class Action { mute, end }
    data class Command(val callID: UUID, val action: Action)
    private val stream = MutableSharedFlow<Command>(extraBufferCapacity = 16)
    val events: SharedFlow<Command> = stream
    @Volatile private var active: UUID? = null
    fun activate(id: UUID) { synchronized(this) { active = id } }
    fun isActive(id: UUID) = active == id
    fun deactivate(id: UUID) { synchronized(this) { if (active == id) active = null } }
    fun send(id: UUID, action: Action): Boolean = active == id && stream.subscriptionCount.value > 0 && stream.tryEmit(Command(id, action))
    companion object Key : DependencyKey<VoiceCallCommands> {
        override val liveValue by lazy { VoiceCallCommands() }
        override val testValue get() = VoiceCallCommands()
    }
}

data class VoiceCallServiceState(val callID: UUID, val phase: Phase = Phase.connecting, val muted: Boolean = false, val connectedAt: Instant? = null) {
    enum class Phase { connecting, live, reconnecting, ended }
}

/** Start from a visible Activity after RECORD_AUDIO permission has been granted,
 * before starting audio; API 35 forbids background microphone FGS admission.
 * :app owns the POST_NOTIFICATIONS permission prompt. It must collect commands
 * before start, update on semantic changes only, and stop when the call ends. */
class VoiceCallServiceClient(
    val start: suspend (VoiceCallServiceState) -> Unit,
    val update: suspend (VoiceCallServiceState) -> Unit,
    val stop: suspend (UUID) -> Unit,
) {
    companion object Key : DependencyKey<VoiceCallServiceClient> {
        override val liveValue by lazy {
            val context = dependency(AppContext); val commands = dependency(VoiceCallCommands)
            VoiceCallServiceClient(
                start = { state -> withContext(Dispatchers.Main.immediate) {
                    // Without the microphone permission the call fails on its own
                    // (microphoneDenied); a microphone service would throw instead.
                    if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        AppLog.voice.notice("voice_call_service_skipped reason=microphone_denied")
                        return@withContext
                    }
                    commands.activate(state.callID)
                    try { context.startForegroundService(VoiceCallService.intent(context, state)) }
                    catch (error: Exception) { commands.deactivate(state.callID); throw error }
                } },
                update = { state -> withContext(Dispatchers.Main.immediate) {
                    if (commands.isActive(state.callID)) context.startService(VoiceCallService.intent(context, state))
                } },
                stop = { id -> withContext(Dispatchers.Main.immediate) {
                    if (commands.isActive(id)) {
                        commands.deactivate(id)
                        context.stopService(Intent(context, VoiceCallService::class.java))
                    }
                } },
            )
        }
        override val testValue = VoiceCallServiceClient({}, {}, {})
    }
}

class VoiceCallService : Service() {
    private var state: VoiceCallServiceState? = null
    private val commands get() = dependency(VoiceCallCommands)
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Calls", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing OpenCode calls"; setSound(null, null); enableVibration(false); setShowBadge(false)
            },
        )
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("callID")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (id == null) { stopSelf(startId); return START_NOT_STICKY }
        when (intent.action) {
            STOP -> if (state == null || state?.callID == id) { state = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
            MUTE, END -> {
                if (state?.callID != id || state?.phase == VoiceCallServiceState.Phase.ended) {
                    if (state == null) stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (!commands.send(id, if (intent.action == MUTE) VoiceCallCommands.Action.mute else VoiceCallCommands.Action.end)) {
                    commands.deactivate(id); state = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
                }
            }
            else -> {
                if (!commands.isActive(id)) { if (state == null) stopSelf(startId); return START_NOT_STICKY }
                val phase = VoiceCallServiceState.Phase.entries.firstOrNull { it.name == intent.getStringExtra("phase") } ?: VoiceCallServiceState.Phase.connecting
                val connected = intent.getLongExtra("connectedAt", -1).takeIf { it >= 0 }?.let(Instant::ofEpochMilli)
                val updated = VoiceCallServiceState(id, phase, intent.getBooleanExtra("muted", false), connected)
                if (updated != state) {
                    // A microphone service may only start with RECORD_AUDIO granted (API 34+ throws
                    // SecurityException otherwise); the call itself reports the denial.
                    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        AppLog.voice.notice("voice_call_service_skipped reason=microphone_denied")
                        if (state == null) stopSelf(startId)
                        return START_NOT_STICKY
                    }
                    state = updated
                    startForeground(NOTIFICATION, notification(updated), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                }
            }
        }
        return START_NOT_STICKY // Never resurrect a microphone after process death.
    }
    private fun notification(state: VoiceCallServiceState): Notification {
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_voice_call_notification)
            .setContentTitle("OpenCode").setCategory(Notification.CATEGORY_CALL).setOngoing(state.phase != VoiceCallServiceState.Phase.ended)
            .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
        val text = when (state.phase) {
            VoiceCallServiceState.Phase.connecting -> "Connecting…"
            VoiceCallServiceState.Phase.reconnecting -> "Reconnecting…"
            VoiceCallServiceState.Phase.ended -> "Call ended"
            VoiceCallServiceState.Phase.live -> null
        }
        if (text != null) builder.setContentText(text).setShowWhen(false)
        else state.connectedAt?.let { builder.setWhen(it.toEpochMilli()).setShowWhen(true).setUsesChronometer(true) }
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setContentIntent(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        if (state.phase != VoiceCallServiceState.Phase.ended) {
            builder.addAction(Notification.Action.Builder(null, if (state.muted) "Unmute" else "Mute", command(state.callID, MUTE)).build())
            builder.addAction(Notification.Action.Builder(null, "End", command(state.callID, END)).build())
        }
        return builder.build()
    }
    private fun command(id: UUID, action: String): PendingIntent = PendingIntent.getService(this, action.hashCode(),
        Intent(this, VoiceCallService::class.java).setAction(action).setData("opencode-call:$id".toUri())
            .putExtra("callID", id.toString()), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    override fun onTaskRemoved(rootIntent: Intent?) {
        state?.let { commands.send(it.callID, VoiceCallCommands.Action.end); commands.deactivate(it.callID) }
        state = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        state?.let { commands.send(it.callID, VoiceCallCommands.Action.end); commands.deactivate(it.callID) }
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "opencode-voice-call"
        private const val NOTIFICATION = 7301
        internal const val STOP = "ai.opencode.voice.STOP"
        private const val MUTE = "ai.opencode.voice.MUTE"
        private const val END = "ai.opencode.voice.END"
        internal fun intent(context: android.content.Context, state: VoiceCallServiceState) = Intent(context, VoiceCallService::class.java)
            .putExtra("callID", state.callID.toString()).putExtra("phase", state.phase.name).putExtra("muted", state.muted)
            .putExtra("connectedAt", state.connectedAt?.toEpochMilli() ?: -1)
    }
}
