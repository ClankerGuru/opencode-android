package ai.opencode.feature.system.notifications

import ai.opencode.core.log.AppLog
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM, the push channel behind [PushTokenProvider] (iOS: APNs and its delegate callbacks).
 * Inert without a Firebase configuration: with no `google-services.json` in the build no
 * `FirebaseApp` exists, so [FcmPushTokenProvider] fails at once and this service never runs.
 */
class OpenCodeMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        SessionNotifications.shared.didRegister(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // The relay's payload: title, body, sessionID, status and ref, as data.
        SessionPushNotifications.handle(applicationContext, message.data)
    }
}

/** The FCM token, when the build carries a Firebase configuration. */
class FcmPushTokenProvider(private val context: Context) : PushTokenProvider {
    override fun register(onToken: (String) -> Unit, onFailure: () -> Unit) {
        val configured = runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
        if (!configured) return onFailure()
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> if (token.isNullOrEmpty()) onFailure() else onToken(token) }
                .addOnFailureListener {
                    AppLog.app.debug("Push token unavailable")
                    onFailure()
                }
        }.onFailure { onFailure() }
    }
}
