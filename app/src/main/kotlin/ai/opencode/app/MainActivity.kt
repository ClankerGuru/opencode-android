package ai.opencode.app

import ai.opencode.core.store.Store
import ai.opencode.feature.system.NotificationDestination
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.share.ShareConversation
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.core.dependencies.dependency
import ai.opencode.feature.composer.DictationPermissionClient
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.feature.system.notifications.NotificationPermissionClient
import ai.opencode.feature.voice.VoiceMicrophonePermission
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import android.os.Bundle
import androidx.activity.ComponentActivity
import ai.opencode.devtools.DevTools
import ai.opencode.devtools.DevToolsOverlay
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel

/**
 * The single activity (iOS's scene). The root [Store] lives in a retained [RootStore] so
 * configuration changes, split screen and freeform resizes keep state and in-flight effects;
 * [AppFeature] holds navigation (connection vs Home) as state, like iOS `AppView`.
 *
 * Intents are iOS's `onOpenURL` and notification taps: [route] turns each into an
 * [AppFeature.Action]. `singleTask` delivers later ones to [onNewIntent].
 */
class MainActivity : ComponentActivity() {
    private val root: RootStore by viewModels()
    private val store get() = root.store

    /** The microphone prompt in flight (one at a time; a second ask joins it). */
    private var microphoneRequest: CompletableDeferred<Boolean>? = null
    private val microphoneLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneRequest?.complete(granted)
        microphoneRequest = null
    }
    private var removeRequesters: List<() -> Unit> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        LaunchBloomController.start()
        splash.setKeepOnScreenCondition { LaunchBloomController.keepsCover(store.state.isColdLaunchContentReady) }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> store.send(AppFeature.Action.ForegroundChanged(true))
                    // iOS `scenePhase == .active`: take what the share sheet left.
                    Lifecycle.Event.ON_RESUME -> {
                        store.send(AppFeature.Action.PendingSharesRequested)
                        registerPermissionRequesters()
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        removeRequesters.forEach { it() }
                        removeRequesters = emptyList()
                    }
                    Lifecycle.Event.ON_STOP -> store.send(AppFeature.Action.ForegroundChanged(false))
                    else -> Unit
                }
            },
        )
        // Developer tools (summon gesture, Tuning Panel, perf HUD): inert outside debug builds unless Developer Mode is on.
        if (savedInstanceState == null) DevTools.handleLaunchIntent(intent)
        setContent { DevToolsOverlay { AppView(store) } }
        // A recreated activity already handled its launch intent.
        if (savedInstanceState == null) handle(intent)
    }

    /**
     * Calls and dictation ask for the microphone as they start, as iOS's
     * `requestRecordPermission()` does. A call's first ask also asks for notifications (once), so
     * its ongoing notification (iOS's Live Activity, which needs no permission) can show.
     */
    private fun registerPermissionRequesters() {
        removeRequesters.forEach { it() }
        removeRequesters = listOf(
            dependency(VoiceMicrophonePermission).register {
                val granted = requestMicrophone()
                val notifications = dependency(NotificationPermissionClient)
                if (granted && notifications.status() == NotificationPermission.NotDetermined) notifications.request()
                granted
            },
            dependency(DictationPermissionClient).register { requestMicrophone() },
        )
    }

    private suspend fun requestMicrophone(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        val pending = microphoneRequest ?: CompletableDeferred<Boolean>().also {
            microphoneRequest = it
            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        return pending.await()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val action = route(intent) ?: return
        LaunchBloomController.completeColdLaunch()
        store.send(action)
    }

    companion object {
        /**
         * iOS `AppView.onOpenURL` and `SessionNotifications.onOpen`: a session notification's tap,
         * an `opencode://` system action link, or a pairing link opened from the camera, a
         * browser, a chat or an `opencode://pair` / `opencode-ios://pair` deep link.
         */
        fun route(intent: Intent?): AppFeature.Action? {
            intent ?: return null
            if (intent.action == NotificationDestination.ACTION_OPEN) {
                val server = intent.getStringExtra(NotificationDestination.EXTRA_SERVER) ?: return null
                val session = intent.getStringExtra(NotificationDestination.EXTRA_SESSION) ?: return null
                return AppFeature.Action.NotificationOpened(NotificationDestination(server, session))
            }
            if (intent.action != Intent.ACTION_VIEW) return null
            val url = intent.dataString ?: return null
            // ScrawlKit's setup links (iOS `.scrawl()`) aren't part of the Android app.
            val host = intent.data?.host?.lowercase()
            if (host == "scrawl" || host == "tether") return null
            // Continue in OpenCode: the share waiting in Shares/ says where to go.
            if (ShareConversation.isContinueURL(url)) return AppFeature.Action.PendingSharesRequested
            SystemAction.fromUrl(url)?.let { return AppFeature.Action.SystemActionReceived(it) }
            if (!OpenCodePairingLink.shouldHandleOpenURL(url)) return null
            return AppFeature.Action.PairingURLOpened(url)
        }
    }
}

/** Holds the app's root store across configuration changes; cancels its effects when the task ends. */
class RootStore : ViewModel() {
    val store = Store(AppFeature.State.initial(), AppFeature())

    override fun onCleared() = store.close()
}
