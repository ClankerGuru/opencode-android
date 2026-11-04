package ai.opencode.app

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.haptics.AudioSessionHaptics
import ai.opencode.core.log.AppLog
import ai.opencode.devtools.DevTools
import ai.opencode.feature.session.AgentActivityLedger
import ai.opencode.feature.transcript.markdown.withMarkdownStream
import ai.opencode.server.EventBatcherHooks
import ai.opencode.server.retry.AppPhase
import ai.opencode.server.retry.AppPhases
import android.app.Application
import ai.opencode.core.dependencies.dependency
import ai.opencode.devtools.DevToolsClient
import ai.opencode.feature.settings.AppVersion
import ai.opencode.feature.system.liveactivity.DictationLiveActivityClient
import ai.opencode.feature.system.liveactivity.SessionLiveActivityClient
import ai.opencode.feature.system.liveactivity.VoiceLiveActivityClient
import ai.opencode.feature.voice.VoiceCallLogClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.util.Log

/**
 * Process entry (iOS `OpenCodeApp` + `AppDelegate` launch work). Installs the application
 * [android.content.Context] as the one process-wide dependency override; everything else is
 * resolved lazily through `DependencyKey.liveValue`. No synchronous disk reads here beyond
 * what the first frame needs (iOS cold-start rule).
 */
class OpenCodeApplication : Application() {
    private val launchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        DependencyValues.installRoot { this[AppContext] = applicationContext }
        // The batcher projects streaming text through the transcript's Markdown stream, so a
        // row's blocks for the same text are a lookup (iOS `EventBatcher.prepareMarkdown`).
        // It also records every event into the agent activity ledger the session glyphs read
        // (iOS `AgentActivityLedger.record` in `OpenCodeClient+Events.swift`).
        DependencyValues.installRoot {
            val markdown = this[EventBatcherHooks].withMarkdownStream()
            this[EventBatcherHooks] = EventBatcherHooks(
                prepareMarkdown = markdown.prepareMarkdown,
                record = { event ->
                    markdown.record(event)
                    AgentActivityLedger.shared.record(event)
                },
            )
        }
        // Foreground/background phases for retries and reconnects, from the first activity start.
        AppPhases.install(this)
        if (BuildConfig.DEBUG) AppLog.minimumPriority = Log.DEBUG
        DevTools.install()
        // Voice's admitted sends, log uploads, diagnostics sinks and screen hold (installed
        // before anything resolves their keys).
        AppDependencies.install(this)
        // Open in New Window (tablet multi-instance windows).
        SceneWindows.install()
        // The haptics diagnostics trace's `dictating=`/`call=` (iOS reads the audio session owner).
        AudioSessionHaptics.isCallActive = { SceneWindows.all.any { it.isResolved && it.store.state.hasLiveCall } }
        AudioSessionHaptics.isDictating = { SceneWindows.all.any { it.isResolved && it.store.state.home?.dictationActivity != null } }
        // iOS `OpenCodeApp.init`'s launch line: version, distribution, model, system.
        val device = dependency(DevToolsClient).device()
        AppLog.app.notice { "Launch: ${AppVersion.current.label}, ${device.distribution}, ${device.model}, ${device.system}" }
        // A call, a recording or a followed run never outlives its process: end any ongoing
        // notification a killed process left behind (iOS ends stale Live Activities at launch).
        val voiceActivity = dependency(VoiceLiveActivityClient)
        val dictationActivity = dependency(DictationLiveActivityClient)
        val sessionActivity = dependency(SessionLiveActivityClient)
        launchScope.launch { voiceActivity.endAll() }
        launchScope.launch { dictationActivity.endAll() }
        launchScope.launch { sessionActivity.endAll() }
        // A call log a previous process left in progress (killed, updated) is saved as
        // interrupted, off the main thread (iOS `OpenCodeApp.init`), and an open call's log is
        // written as the app leaves the foreground (iOS `scenePhase == .background`). Resolved
        // after AppDependencies so the recorder gets the installed uploader.
        val voiceCallLog = dependency(VoiceCallLogClient)
        launchScope.launch(Dispatchers.IO) { voiceCallLog.recoverInterrupted() }
        launchScope.launch {
            AppPhases.phases().collect { phase ->
                if (phase == AppPhase.Background) launch(Dispatchers.IO) { voiceCallLog.persistOpenCalls() }
            }
        }
    }
}
