package ai.opencode.feature.session

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.StoreOf
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceLabDemo
import ai.opencode.feature.voice.VoiceLabTrigger
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Port of iOS DEBUG `VoiceLabTriggers` (`Voice/VoiceLabTriggers.swift`): while the voice lab runs
 * ([VoiceLabDemo.isEnabled], debug builds only), the visible session acts on the lab's triggers so
 * a test can start a call, open its transcript, or fire Raise to Talk while something covers the
 * call button or bar. iOS posts them as Darwin notifications; here they're broadcasts named
 * [VoiceLabTrigger.notification] (`adb shell am broadcast -a OpenCodeLab.ai.opencode.lab.voice-start`).
 */
@Composable
internal fun SessionVoiceLabTriggers(store: StoreOf<SessionFeature.State, SessionFeature.Action>, isEnabled: Boolean) {
    val active = isEnabled && VoiceLabDemo.isEnabled
    DisposableEffect(store, active) {
        if (!active) return@DisposableEffect onDispose {}
        val context = dependency(AppContext).applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    VoiceLabTrigger.startCall.notification ->
                        if (store.state.voice == null) store.send(SessionFeature.Action.VoiceTapped)
                    VoiceLabTrigger.showCaptions.notification ->
                        store.send(SessionFeature.Action.Voice(PresentationAction.Presented(VoiceFeature.Action.CaptionsTapped)))
                    VoiceLabTrigger.raiseToEar.notification -> store.send(SessionFeature.Action.RaisedToEar)
                }
            }
        }
        val filter = IntentFilter().apply { VoiceLabTrigger.entries.forEach { addAction(it.notification) } }
        val registered = runCatching { context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) }.isSuccess
        onDispose { if (registered) runCatching { context.unregisterReceiver(receiver) } }
    }
}
