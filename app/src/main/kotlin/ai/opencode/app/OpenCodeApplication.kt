package ai.opencode.app

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.log.AppLog
import ai.opencode.devtools.DevTools
import ai.opencode.feature.session.AgentActivityLedger
import ai.opencode.feature.transcript.markdown.withMarkdownStream
import ai.opencode.server.EventBatcherHooks
import ai.opencode.server.retry.AppPhases
import android.app.Application
import android.util.Log

/**
 * Process entry (iOS `OpenCodeApp` + `AppDelegate` launch work). Installs the application
 * [android.content.Context] as the one process-wide dependency override; everything else is
 * resolved lazily through `DependencyKey.liveValue`. No synchronous disk reads here beyond
 * what the first frame needs (iOS cold-start rule).
 */
class OpenCodeApplication : Application() {
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
        AppLog.app.info { "app_launch version=${BuildConfig.VERSION_NAME} build=${BuildConfig.VERSION_CODE}" }
    }
}
