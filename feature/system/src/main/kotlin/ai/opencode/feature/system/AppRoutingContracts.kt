package ai.opencode.feature.system

import ai.opencode.core.dependencies.DependencyKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.net.URI
import java.security.MessageDigest

/*
 * CONTRACT STUBS written by area/app so `AppFeature` can route system actions and notification
 * taps before :feature:system lands. They port the iOS shapes exactly (names, fields, URL
 * grammar). The system area owns them: replace each with the full port (iOS
 * `System/Intents/SystemAction.swift`, `SystemIntegrationClient.swift`,
 * `SessionDirectory.serverKey`, `System/Notifications/SessionNotifications.swift`
 * `NotificationDestination`) keeping these names, then delete this file.
 */

/**
 * Where Siri, Shortcuts, quick actions (app shortcuts), widgets and `opencode://` links take you
 * (iOS `SystemAction`). Every surface makes one of these, and `AppFeature` routes it like a
 * tapped notification.
 */
data class SystemAction(
    /** [SessionDirectory.serverKey]; null means the server on screen. */
    val serverKey: String? = null,
    val destination: Destination,
    /** Starts a voice call there through the call button's own path. */
    val startsCall: Boolean = false,
    /** The app stays in the background: the call starts without its view appearing. */
    val startsInBackground: Boolean = false,
) {
    sealed interface Destination {
        /** The session you'd see on opening the app: the last viewed, else the newest. */
        data object LastViewed : Destination
        data class Session(val id: String) : Destination
        /** A New Session draft, in a project when one is named. */
        data class Draft(val projectID: String?) : Destination
    }

    companion object {
        const val URL_SCHEME = "opencode"

        /**
         * `opencode://session/<id>?server=<key>`, `opencode://last-session?server=<key>` and
         * `opencode://new-session?server=<key>&project=<id>`. Links only open: a web page must
         * never be able to start a call (iOS `SystemAction(url:)`).
         */
        fun fromUrl(url: String): SystemAction? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() != URL_SCHEME) return null
            val query = HashMap<String, String>()
            uri.rawQuery?.split("&")?.forEach { item ->
                val parts = item.split("=", limit = 2)
                if (parts.size == 2) query.putIfAbsent(percentDecoded(parts[0]), percentDecoded(parts[1]))
            }
            val path = (uri.rawPath ?: "").split("/").filter { it.isNotEmpty() }.map(::percentDecoded)
            return when (uri.host?.lowercase()) {
                "session" -> if (path.size == 1 && path[0].isNotEmpty()) {
                    SystemAction(serverKey = query["server"], destination = Destination.Session(path[0]))
                } else null
                "last-session" -> SystemAction(serverKey = query["server"], destination = Destination.LastViewed)
                "new-session" -> SystemAction(serverKey = query["server"], destination = Destination.Draft(query["project"]))
                else -> null
            }
        }

        /** `URLComponents.queryItems` decoding: percent escapes only (`+` stays a plus). */
        private fun percentDecoded(text: String): String {
            if (!text.contains('%')) return text
            val bytes = java.io.ByteArrayOutputStream()
            var index = 0
            while (index < text.length) {
                val char = text[index]
                if (char == '%' && index + 2 < text.length) {
                    val value = text.substring(index + 1, index + 3).toIntOrNull(16)
                    if (value != null) {
                        bytes.write(value)
                        index += 3
                        continue
                    }
                }
                bytes.write(char.toString().toByteArray(Charsets.UTF_8))
                index++
            }
            return bytes.toString(Charsets.UTF_8.name())
        }
    }
}

/** iOS `SessionDirectory` (only the key derivation the app needs). */
object SessionDirectory {
    /**
     * A short, stable, non-reversible name for a saved server, so session IDs handed to the
     * system never carry its address or username (first 6 bytes of SHA-256, hex).
     */
    fun serverKey(persistenceID: String): String =
        MessageDigest.getInstance("SHA-256").digest(persistenceID.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it) }
}

/**
 * Carries actions from shortcuts, widgets and the activity to `AppFeature`. Actions posted
 * before anything listens (a shortcut that launched the app) wait for the first listener; the
 * newest listener replaces any earlier one (iOS `SystemActionInbox`).
 */
private object SystemActionInbox {
    private val lock = Any()
    private val waiting = ArrayList<SystemAction>()
    private var listener: Channel<SystemAction>? = null

    fun post(action: SystemAction) = synchronized(lock) {
        val channel = listener
        if (channel != null && channel.trySend(action).isSuccess) return@synchronized
        listener = null
        waiting += action
    }

    fun actions(): Flow<SystemAction> {
        val channel = Channel<SystemAction>(Channel.UNLIMITED)
        val replaced = synchronized(lock) {
            val replaced = listener
            listener = channel
            waiting.forEach { channel.trySend(it) }
            waiting.clear()
            replaced
        }
        replaced?.close()
        channel.invokeOnClose { synchronized(lock) { if (listener === channel) listener = null } }
        return channel.receiveAsFlow()
    }
}

/**
 * The app's side of the system integrations (iOS `SystemIntegrationClient`): where shortcuts,
 * widgets and links arrive. `publish` (session directory, shortcuts, widgets) is the system
 * area's to add.
 */
data class SystemIntegrationClient(
    /** Every action from a shortcut or widget, starting with any that arrived before the app listened. */
    val actions: () -> Flow<SystemAction>,
    /** Hands an action to the app (shortcut and widget intents, the activity). */
    val post: (SystemAction) -> Unit,
    /** Forgets a server that is no longer saved. */
    val removeServer: (serverKey: String) -> Unit,
) {
    companion object Key : DependencyKey<SystemIntegrationClient> {
        override val liveValue = SystemIntegrationClient(
            actions = { SystemActionInbox.actions() },
            post = { SystemActionInbox.post(it) },
            removeServer = {},
        )
        override val testValue = SystemIntegrationClient(actions = { emptyFlow() }, post = {}, removeServer = {})
    }
}

/** A tapped session notification's target (iOS `NotificationDestination`); [serverKey] is the server's persistence ID. */
data class NotificationDestination(val serverKey: String, val sessionID: String) {
    val isValid: Boolean
        get() = serverKey.isNotEmpty() && serverKey.toByteArray(Charsets.UTF_8).size <= 512 &&
            sessionID.startsWith("ses") && sessionID.toByteArray(Charsets.UTF_8).size <= 200 &&
            sessionID.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it == '_' }

    companion object {
        /** The action of a session notification's explicit content intent to `MainActivity`. */
        const val ACTION_OPEN = "ai.opencode.action.OPEN_SESSION_NOTIFICATION"
        /** Extra keys, as iOS's notification payload (`ShareConversation.notificationPayload`). */
        const val EXTRA_SERVER = "server"
        const val EXTRA_SESSION = "session"
    }
}
