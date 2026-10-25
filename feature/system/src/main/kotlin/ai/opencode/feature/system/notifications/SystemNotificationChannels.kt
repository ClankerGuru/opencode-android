package ai.opencode.feature.system.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Android notification channels for what iOS shows as Live Activities and notifications.
 * Created on first use (idempotent). One per kind, so each can be silenced on its own.
 */
object SystemNotificationChannels {
    /** A voice call's Live Update (iOS: the call's Live Activity). */
    const val VOICE_CALL = "voice-call"
    /** A hands-free recording's Live Update (iOS: the dictation Live Activity). */
    const val DICTATION = "dictation"
    /** A session finished or needs input (iOS: the relay's push). */
    const val SESSIONS = "sessions"
    /** The share sheet's “sent” and “continue” notices (iOS: `ShareNotifier`). */
    const val SHARE = "share"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(VOICE_CALL, "Voice Calls", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
                NotificationChannel(DICTATION, "Recording", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
                NotificationChannel(SESSIONS, "Session Updates", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(SHARE, "Shared Items", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setShowBadge(false)
                },
            ),
        )
    }
}
