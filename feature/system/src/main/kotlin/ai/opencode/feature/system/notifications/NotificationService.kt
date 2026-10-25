package ai.opencode.feature.system.notifications

import ai.opencode.core.log.AppLog
import ai.opencode.feature.system.R
import ai.opencode.feature.system.intents.SystemActionActivity
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Port of `OpenCodeNotifications/NotificationService.swift` plus the app delegate's
 * presentation: turns a session push's data (`title`, `body`, `sessionID`, `status`, `ref`,
 * the relay's whole payload) into a notification. iOS's service extension only adds the
 * badge; on Android the app posts the notification itself, so this also decides how it shows
 * in the foreground and where a tap leads.
 *
 * The transport (FCM's `onMessageReceived`) calls [handle]; nothing calls it until a push
 * channel exists (see [SessionNotifications]).
 */
object SessionPushNotifications {
    /** iOS `DeliveredNotifications.needsInputStatuses`: statuses a needs-input notification carries. */
    val needsInputStatuses = setOf("needs-input", "permission", "question", "form")

    private const val EXTRA_SESSION = "opencode.sessionID"
    private const val EXTRA_STATUS = "opencode.status"

    @SuppressLint("MissingPermission")
    fun handle(context: Context, data: Map<String, String>) {
        val title = data["title"].orEmpty()
        val body = data["body"].orEmpty()
        val sessionID = data["sessionID"]
        val ref = data["ref"]
        val status = data["status"]
        val notifications = SessionNotifications.shared
        val presentation = notifications.presentation(sessionID)
        if (presentation == NotificationPresentation.None) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        SystemNotificationChannels.ensure(context)
        val builder = NotificationCompat.Builder(context, SystemNotificationChannels.SESSIONS)
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSilent(presentation == NotificationPresentation.ListOnly)
            .setPriority(if (presentation == NotificationPresentation.ListOnly) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
        // The thread is the session (iOS `threadIdentifier`).
        sessionID?.let { builder.setGroup(it) }
        builder.extras.putString(EXTRA_SESSION, sessionID)
        builder.extras.putString(EXTRA_STATUS, status)
        // The badge the ledger counts for its session (iOS `NotificationService.badged`).
        if (ref != null && sessionID != null) {
            val defaults = AppBadgeLedger.groupDefaults(context)
            AppBadgeLedger.load(defaults)?.badgeAfterPush(sessionID = sessionID, ref = ref)?.let { (ledger, badge) ->
                ledger.save(defaults)
                builder.setNumber(badge)
            }
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context, sessionID.hashCode(), SystemActionActivity.pushIntent(context, ref, sessionID),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        runCatching { NotificationManagerCompat.from(context).notify(sessionID, NOTIFICATION_ID, builder.build()) }
            .onFailure { AppLog.app.error { "Session notification not posted: ${it::class.java.simpleName}" } }
    }

    /**
     * Port of iOS `DeliveredNotifications.remove`: every notification for these sessions, or
     * only their needs-input ones once the request is answered. The drawer's
     * `DeliveredNotificationsClient` calls this.
     */
    fun remove(context: Context, sessionIDs: Set<String>, needsInputOnly: Boolean) {
        if (sessionIDs.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (notification in manager.activeNotifications) {
            if (notification.id != NOTIFICATION_ID) continue
            val extras = notification.notification.extras
            val id = extras.getString(EXTRA_SESSION) ?: notification.tag ?: continue
            if (id !in sessionIDs) continue
            if (needsInputOnly && extras.getString(EXTRA_STATUS) !in needsInputStatuses) continue
            manager.cancel(notification.tag, notification.id)
        }
    }

    private const val NOTIFICATION_ID = 4201
}
