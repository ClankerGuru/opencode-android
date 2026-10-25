package ai.opencode.feature.system.notifications

import ai.opencode.feature.system.NotificationDestination
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.InstantSerializer
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.server.OpenCodeServer
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

enum class NotificationPermission { NotDetermined, Disabled, Enabled }

/** Port of iOS `NotificationPermissionClient`. */
class NotificationPermissionClient(
    val status: suspend () -> NotificationPermission,
    val request: suspend () -> NotificationPermission,
    val openSettings: suspend () -> Boolean,
) {
    companion object Key : DependencyKey<NotificationPermissionClient> {
        override val liveValue: NotificationPermissionClient by lazy {
            NotificationPermissionClient(
                status = { SessionNotifications.shared.permissionStatus() },
                request = { SessionNotifications.shared.requestPermission() },
                openSettings = {
                    val context = dependency(AppContext)
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.isSuccess
                },
            )
        }
        override val testValue = NotificationPermissionClient(
            status = { NotificationPermission.Enabled },
            request = { NotificationPermission.Enabled },
            openSettings = { true },
        )
    }
}

/**
 * Port of iOS `SessionNotificationsClient`: the callback URL a send attaches so the server's
 * notify plugin can tell this phone when the run ends. Null means "send without one":
 * notifications only ever enhance a send and never delay or fail it.
 *
 * Senders bind it with `PromptContext.notificationCallbackURL.withValue(url) { … }` (iOS
 * `PromptNotification.$callbackURL`), which `:server` reads when building the prompt body.
 */
class SessionNotificationsClient(
    /** Whether this build has a relay and a push channel to offer at all. */
    val isAvailable: Boolean,
    val callbackURL: suspend (OpenCodeServer) -> String?,
    /** Mints ahead of the first send so Send rarely waits on the relay. */
    val prepare: suspend (OpenCodeServer) -> Unit,
    /** Drops a removed server's ref and callback (by persistence ID). */
    val forget: suspend (String) -> Unit,
    /** The random `ref` a server's pushes carry, made and saved the first time it's asked for. */
    val ref: (String) -> String = { "" },
    /** iOS adds the badge to a permission granted before the app asked; Android grants the count with notifications. */
    val includeBadges: suspend () -> Unit = {},
    /** Tells notifications which session is on screen so its own don't show. */
    val setVisibleSession: (shown: Boolean, sessionID: String?) -> Unit = { _, _ -> },
    /** Whether the drawer button can show In-App Notices' label. */
    val setShowsDrawerLabel: (Boolean) -> Unit = {},
    /**
     * In-App Notices' mode as the drawer reads it (`InAppNoticeMode`, in `:feature:drawer`):
     * true while it's anything but Off. The drawer installs its reader at launch.
     */
    val setInAppNoticesProvider: (() -> Boolean) -> Unit = {},
) {
    companion object Key : DependencyKey<SessionNotificationsClient> {
        override val liveValue: SessionNotificationsClient by lazy {
            SessionNotificationsClient(
                isAvailable = SessionNotifications.isAvailable,
                callbackURL = { SessionNotifications.shared.callbackURL(it) },
                prepare = { SessionNotifications.shared.callbackURL(it) },
                forget = { SessionNotifications.shared.forget(it) },
                ref = { SessionNotifications.shared.ref(it) },
                includeBadges = {},
                setVisibleSession = { shown, sessionID ->
                    val id = if (shown) sessionID else null
                    if (shown || SessionNotifications.shared.visibleSessionID == sessionID) {
                        SessionNotifications.shared.visibleSessionID = id
                    }
                },
                setShowsDrawerLabel = { SessionNotifications.shared.showsDrawerLabel = it },
                setInAppNoticesProvider = { SessionNotifications.shared.inAppNoticesOn = it },
            )
        }
        override val testValue = SessionNotificationsClient(
            isAvailable = true,
            callbackURL = { null },
            prepare = {},
            forget = {},
        )
    }
}

/**
 * How a session notification arriving in the foreground shows (iOS
 * `DeliveredNotifications.presentation`). The session on screen shows nothing; while In-App
 * Notices is on, the drawer button tells of the others, so they go only to the shade.
 */
enum class NotificationPresentation { None, Banner, ListOnly }

/**
 * Port of iOS `SessionNotifications`: push registration and relay callback URLs.
 *
 * Privacy: the relay is stateless. It seals the push token, the build's push environment and
 * a random per-server `ref` into the callback URL itself, so the OpenCode server never sees
 * the token and the relay keeps nothing. The `ref` exists only so a tap can find the saved
 * server; it is random, never derived from the server's address or credentials.
 *
 * The relay (`packages/notify-relay`) speaks APNs only today: it accepts hex tokens with an
 * environment of `development`/`production`. Android pushes would ride FCM, whose tokens it
 * can't seal yet, so [environment] stays null and [isAvailable] false until it does. The
 * whole path (permission, token wait, mint, saved callbacks, tap routing, the push handler)
 * is in place behind [PushTokenProvider].
 */
class SessionNotifications internal constructor(private val tokens: PushTokenProvider) {
    /** The session on screen while the app is in the foreground. Its own "finished" notifications aren't shown. */
    @Volatile var visibleSessionID: String? = null
    /** Whether the drawer button can show In-App Notices' label; beside a wide layout's sidebar it can't. */
    @Volatile var showsDrawerLabel = true
    /** In-App Notices' mode, read live; Off until the drawer installs its reader. */
    @Volatile var inAppNoticesOn: () -> Boolean = { false }

    fun presentation(sessionID: String?): NotificationPresentation {
        sessionID ?: return NotificationPresentation.Banner
        if (sessionID == visibleSessionID) return NotificationPresentation.None
        val off = !(inAppNoticesOn() && showsDrawerLabel)
        return if (off) NotificationPresentation.Banner else NotificationPresentation.ListOnly
    }

    /** A tapped notification's destination, delivered once something routes it (iOS `onOpen`). */
    var onOpen: ((NotificationDestination) -> Unit)? = null
        set(value) {
            field = value
            val pending = pendingDestination
            if (value != null && pending != null) {
                pendingDestination = null
                value(pending)
            }
        }

    private var pendingDestination: NotificationDestination? = null
    @Volatile private var confirmedToken: String? = null
    private val lock = Any()
    private val mintTasks = HashMap<String, Deferred<Minted?>>()
    private val tokenWaiters = mutableListOf<CompletableDeferred<String?>>()
    private val saved get() = shared(savedKey)
    private var permissionRequest: CompletableDeferred<Boolean>? = null
    private val mintScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Serializable
    data class Saved(
        /** Server persistence ID → its random ref. */
        val refs: Map<String, String> = emptyMap(),
        /** Server persistence ID → its current callback. */
        val callbacks: Map<String, Callback> = emptyMap(),
    )

    @Serializable
    data class Callback(
        val url: String,
        @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
        val token: String,
        val environment: String,
    )

    data class Minted(val url: String, val expiresAt: Instant)

    fun permissionStatus(): NotificationPermission {
        val context = dependency(AppContext)
        if (NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) return NotificationPermission.Enabled
        val asked = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ASKED, false)
        return if (asked) NotificationPermission.Disabled else NotificationPermission.NotDetermined
    }

    /** Asks once (Android's dialog, from a see-through activity), then registers for pushes when allowed. */
    suspend fun requestPermission(): NotificationPermission {
        if (permissionStatus() == NotificationPermission.NotDetermined) {
            val request = synchronized(lock) {
                permissionRequest ?: CompletableDeferred<Boolean>().also { created ->
                    permissionRequest = created
                    val context = dependency(AppContext)
                    context.startActivity(
                        Intent(context, NotificationPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            request.await()
        }
        val status = permissionStatus()
        if (status == NotificationPermission.Enabled) registerForRemoteNotifications()
        return status
    }

    internal fun permissionAnswered(granted: Boolean) {
        val context = dependency(AppContext)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
        val request = synchronized(lock) { permissionRequest.also { permissionRequest = null } }
        request?.complete(granted)
    }

    /** The random `ref` a server's pushes carry, made and saved the first time it's asked for. */
    fun ref(key: String): String {
        var made: String? = null
        saved.update { current ->
            val existing = current.refs[key]
            if (existing != null) {
                made = existing
                current
            } else {
                val ref = UUID.randomUUID().toString().lowercase()
                made = ref
                current.copy(refs = current.refs + (key to ref))
            }
        }
        return made ?: saved.value.refs[key].orEmpty()
    }

    /**
     * A callback URL for [server], minting one when the saved one is missing, stale, or for an
     * older token. Null whenever notifications are unavailable.
     */
    suspend fun callbackURL(server: OpenCodeServer): String? {
        val environment = environment
        if (!isAvailable || environment == null) return null
        if (permissionStatus() != NotificationPermission.Enabled || !currentCoroutineContext().isActive) return null
        registerForRemoteNotifications()
        val token = waitForToken() ?: return null
        if (!currentCoroutineContext().isActive) return null
        val key = server.persistenceID
        saved.value.callbacks[key]?.let { callback ->
            if (callback.token == token && callback.environment == environment &&
                callback.expiresAt.toEpochMilli() - Instant.now().toEpochMilli() > 3.days.inWholeMilliseconds
            ) return callback.url
        }
        val ref = ref(key)
        val task = synchronized(lock) {
            mintTasks[key] ?: mintScope.async { mint(token, environment, ref) }.also { mintTasks[key] = it }
        }
        val minted = task.await()
        synchronized(lock) { if (mintTasks[key] === task) mintTasks.remove(key) }
        // A server forgotten meanwhile keeps no callback.
        if (minted == null || confirmedToken != token || saved.value.refs[key] != ref) return null
        saved.update { it.copy(callbacks = it.callbacks + (key to Callback(minted.url, minted.expiresAt, token, environment))) }
        return minted.url
    }

    /** Forgets a removed server's ref and callback. */
    fun forget(serverKey: String) {
        saved.update { it.copy(refs = it.refs - serverKey, callbacks = it.callbacks - serverKey) }
    }

    fun didRegister(token: String) {
        if (token.isEmpty()) return
        confirmedToken = token
        finishTokenWaiters(token)
    }

    fun registrationFailed() {
        confirmedToken = null
        finishTokenWaiters(null)
        AppLog.app.debug("Remote notification registration unavailable; sending without a callback.")
    }

    /** The saved server a tapped notification's `ref` was minted for, with its session; null when unknown or invalid. */
    fun destination(ref: String, sessionID: String): NotificationDestination? {
        val serverKey = saved.value.refs.entries.firstOrNull { it.value == ref }?.key ?: return null
        return NotificationDestination(serverKey, sessionID).takeIf { it.isValid }
    }

    /** Routes a tapped notification's `ref` to the server it was minted for. */
    fun open(ref: String, sessionID: String) {
        val destination = destination(ref, sessionID) ?: return
        val onOpen = onOpen
        if (onOpen != null) onOpen(destination) else pendingDestination = destination
    }

    private fun registerForRemoteNotifications() {
        tokens.register(::didRegister, ::registrationFailed)
    }

    private suspend fun waitForToken(): String? {
        confirmedToken?.let { return it }
        val waiter = CompletableDeferred<String?>()
        synchronized(lock) { tokenWaiters += waiter }
        return try {
            withTimeoutOrNull(1500.milliseconds) { waiter.await() }
        } finally {
            synchronized(lock) { tokenWaiters.remove(waiter) }
        }
    }

    private fun finishTokenWaiters(token: String?) {
        val waiters = synchronized(lock) { tokenWaiters.toList().also { tokenWaiters.clear() } }
        for (waiter in waiters) waiter.complete(token)
    }

    companion object {
        /** The relay's origin (`NotifyRelayURL`). */
        const val RELAY_URL = "https://opencode-notify-relay.anomalyco.workers.dev"

        /**
         * The push environment the relay seals (`APNSEnvironment` on iOS). Null: the relay has
         * no FCM delivery yet (see the class comment), so Android builds offer no callback.
         */
        val environment: String? = null

        /** Whether this build can offer notifications at all. */
        val isAvailable: Boolean get() = environment != null && RELAY_URL.toHttpUrlOrNull()?.isHttps == true

        private const val PREFERENCES = "session-notifications"
        private const val ASKED = "permission-asked"

        private val savedKey = SharedKey.keychain("session-notification-callbacks", Saved())

        val shared: SessionNotifications by lazy { SessionNotifications(FcmPushTokenProvider(dependency(AppContext).applicationContext)) }

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * `POST /v1/register` on the relay. Fails open within three seconds, never follows a
         * redirect, and sends nothing but the token, environment, and ref.
         */
        internal fun mint(token: String, environment: String, ref: String): Minted? {
            val relay = RELAY_URL.toHttpUrlOrNull() ?: return null
            val body = buildJsonObject {
                put("token", token)
                put("environment", environment)
                put("ref", ref)
            }.toString()
            val client = OkHttpClient.Builder()
                .callTimeout(3, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .cache(null)
                .build()
            val request = Request.Builder()
                .url(relay.newBuilder().addPathSegments("v1/register").build())
                .header("Content-Type", "application/json")
                .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            return try {
                client.newCall(request).execute().use { response ->
                    if (response.code != 200) return null
                    val output = json.parseToJsonElement(response.body.string()) as? JsonObject ?: return null
                    val url = output["callbackURL"]?.jsonPrimitive?.content ?: return null
                    val expiresAt = output["expiresAt"]?.jsonPrimitive?.content?.let(::parseDate) ?: return null
                    val parsed = url.toHttpUrlOrNull() ?: return null
                    if (!parsed.isHttps || parsed.host != relay.host) return null
                    Minted(url, expiresAt)
                }
            } catch (_: Exception) {
                // Never log the token, the callback URL, or the response.
                AppLog.app.debug("Notification relay unavailable; sending without a callback.")
                null
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }

        /** The relay writes `Date.toISOString()`, which carries milliseconds. */
        private fun parseDate(value: String): Instant? =
            runCatching { Instant.parse(value) }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    }
}

/**
 * The push channel's token, behind an interface (iOS: APNs registration). Inert until a
 * Firebase configuration ships with the build: no `google-services.json` exists, so the live
 * provider reports failure at once and every send goes without a callback.
 */
fun interface PushTokenProvider {
    /** Asks for the token; exactly one of the callbacks runs, now or later. */
    fun register(onToken: (String) -> Unit, onFailure: () -> Unit)

    companion object {
        /** No push channel: every registration fails at once. */
        val inert: PushTokenProvider = PushTokenProvider { _, onFailure -> onFailure() }
    }
}
