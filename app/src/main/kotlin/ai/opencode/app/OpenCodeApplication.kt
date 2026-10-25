package ai.opencode.app

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.log.AppLog
import ai.opencode.devtools.DevTools
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
        // Foreground/background phases for retries and reconnects, from the first activity start.
        AppPhases.install(this)
        if (BuildConfig.DEBUG) AppLog.minimumPriority = Log.DEBUG
        DevTools.install()
        AppLog.app.info { "app_launch version=${BuildConfig.VERSION_NAME} build=${BuildConfig.VERSION_CODE}" }
    }
}
