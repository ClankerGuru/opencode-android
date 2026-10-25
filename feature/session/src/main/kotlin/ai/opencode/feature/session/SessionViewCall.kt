package ai.opencode.feature.session

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.experiments.key
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.voice.FloatingCallCenter
import ai.opencode.feature.voice.LiveSocketWarmer
import ai.opencode.feature.voice.LiveSocketWarmerClient
import ai.opencode.feature.voice.RaiseToEarClient
import ai.opencode.feature.voice.VoiceCallEnded
import ai.opencode.feature.voice.VoiceCallFailure
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceLabDemo
import ai.opencode.feature.voice.VoiceStoreOf
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

// The call's part of iOS `SessionView`: which store is the screen's call (`callStore`), its
// presentation (`callPresentation`/`voiceStore`), the failed and ended cards above the composer,
// and the screen's `.task`s that start it once, listen for Raise to Talk and keep a GPT-Live socket
// warm. The bar itself is :feature:voice's `VoiceCallComposer` on `VoiceCallSurface`, swapped into
// the composer's place by `SessionComposer`.

/**
 * iOS `callStore`: the call on this screen, its own (`store.scope(\.voice, action: \.voice.presented)`)
 * or the one its root owns ([hostedCall], a pushed subagent's).
 */
@Composable
internal fun StoreOf<State, Action>.rememberCallStore(hostedCall: VoiceStoreOf?): VoiceStoreOf? =
    rememberOptionalScope<VoiceFeature.State, VoiceFeature.Action>({ it.voice }, { Action.voicePresented(it) }) ?: hostedCall

/**
 * iOS `floatsCall` (`callPresentation == .floating`): Floating Call or Living Orb is on, so the call
 * floats over the screen (Home hosts `FloatingCallOverlay`) and the composer stays a composer.
 * The Call Tray takes precedence on iOS only when Home hands the screen a `CallTrayController`;
 * Android's Home doesn't host the tray yet, so the tray style never applies here.
 */
@Composable
internal fun rememberFloatsCall(): Boolean {
    val center = remember { dependency(FloatingCallCenter) }
    val floating by remember { shared(ExperimentalFeature.floatingCall.key).flow }.collectAsState(shared(ExperimentalFeature.floatingCall.key).value)
    val livingOrb by remember { shared(ExperimentalFeature.livingOrb.key).flow }.collectAsState(shared(ExperimentalFeature.livingOrb.key).value)
    return ExperimentalFeature.livingOrb.isActive(livingOrb) || center.isOn(floating)
}

/**
 * The screen's call tasks (iOS `sessionScaffold`'s `.task`s and the composer area's `.task(id: hasCall)`):
 * - once per call, the call starts (`.startIfNeeded`): it keeps running while the drawer swaps sessions;
 * - Raise to Talk listens while the setting is on, Voice is available and the app is active; the
 *   reducer decides what a raise does;
 * - a GPT-Live socket kept open (no session started, so free) while a call could start here, so a
 *   tap skips the connection setup.
 */
@Composable
internal fun SessionCallTasks(store: StoreOf<State, Action>, callStore: VoiceStoreOf?, hasCall: Boolean) {
    val currentCall by rememberUpdatedState(callStore)
    LaunchedEffect(hasCall) { currentCall?.send(VoiceFeature.Action.StartIfNeeded) }

    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val raiseToTalk by remember { shared(SharedKeys.raiseToTalk).flow }.collectAsState(shared(SharedKeys.raiseToTalk).value)
    val isVoiceAvailable by store.observe { it.isVoiceAvailable }
    LaunchedEffect(raiseToTalk && isVoiceAvailable && active) {
        if (!(raiseToTalk && isVoiceAvailable && active)) return@LaunchedEffect
        dependency(RaiseToEarClient).monitor(
            {
                // The reducer decides; its sends run before this returns.
                store.send(Action.RaisedToEar)
                store.state.voice != null
            },
            { near -> store.send(Action.AtEarChanged(near)) },
        )
    }

    val warmsPhoneConnection by store.observe { it.voiceRoute.warmsPhoneConnection }
    val currentHasCall by rememberUpdatedState(hasCall)
    LaunchedEffect(warmsPhoneConnection && isVoiceAvailable && !hasCall && active) {
        if (!(warmsPhoneConnection && isVoiceAvailable && !hasCall && active)) return@LaunchedEffect
        // iOS reads the lab's key in DEBUG builds; VoiceLabEnvironment answers null in release ones.
        val stored = store.state.openAIAPIKey.value ?: withContext(Dispatchers.IO) { VoiceLabDemo.labAPIKey }
        val key = stored?.trim()?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        val warmer = dependency(LiveSocketWarmerClient)
        try {
            while (true) {
                warmer.warm(key)
                delay((LiveSocketWarmer.maxAge / 2).seconds)
            }
        } finally {
            // Still here means the screen left or a call began (and took it).
            if (!currentHasCall) warmer.cool()
        }
    }
}

/**
 * iOS `safeAreaInset`'s cards above the composer: a call that couldn't connect
 * ([VoiceCallFailure]: Check API Key or Open Settings, and Try Again) or whose connection was lost
 * ([VoiceCallEnded]: Close and Rejoin).
 */
@Composable
internal fun SessionCallCards(callStore: VoiceStoreOf?) {
    val call = callStore ?: return
    val phase by call.observe { it.phase }
    val context = LocalContext.current
    when (val current = phase) {
        is VoiceFeature.State.Phase.Failed -> VoiceCallFailure(
            store = call,
            message = current.message,
            onOpenSettings = {
                // The microphone was denied: the app's page in system settings (iOS `openSettingsURLString`).
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                }
            },
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
        )
        VoiceFeature.State.Phase.Dropped -> VoiceCallEnded(call, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp))
        else -> Unit
    }
}
