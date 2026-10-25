package ai.opencode.app

import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.ModelVisibility
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Port of iOS `OpenCodeApp`'s DEBUG lab launch hooks for UI tests and lab runs. iOS reads launch
 * arguments and `OPENCODE_LAB_*` environment variables; Android reads the launch intent's extras
 * (`adb shell am start -n …/ai.opencode.app.MainActivity --es lab-server-url … --ez lab-fresh-install true`).
 * Debug builds only, and applied before anything reads the store, as iOS runs them before
 * `OpenCodeApp.store`.
 *
 * - `lab-fresh-install` (`OPENCODE_LAB_FRESH_INSTALL=1`): forgets the saved servers, so onboarding opens.
 * - With `lab-server-url` (`OPENCODE_LAB_SERVER_URL`) set, which identifies a lab launch:
 *   - `lab-reset-model-preferences` (`-lab-reset-model-preferences`): no favourite, recent or hidden models.
 *   - `lab-pasteboard-text` `log` | `big` | `lines` | `paragraph` (`-lab-pasteboard-text`).
 * - `lab-openai-key` / `lab-xai-key` (`OPENCODE_LAB_OPENAI_KEY` / `_XAI_KEY`, iOS `LabVoiceKeys`;
 *   empty or `NONE` clears).
 */
internal object LabLaunch {
    fun apply(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        if (intent.getBooleanExtra("lab-fresh-install", false)) {
            shared(ServerSharedKeys.savedServer).set(null)
            shared(ServerSharedKeys.savedServers).set(emptyList())
            AppLog.app.info { "lab fresh install: saved servers forgotten" }
        }
        val isLab = intent.getStringExtra("lab-server-url") != null
        if (isLab && intent.getBooleanExtra("lab-reset-model-preferences", false)) {
            shared(SharedKeys.favoriteModelIDs).set(emptySet())
            shared(SharedKeys.recentModelIDs).set(emptyList())
            shared(ModelVisibility.modelVisibilityByServer).set(emptyMap())
        }
        if (isLab) intent.getStringExtra("lab-pasteboard-text")?.let(::pasteboardText)?.let { text ->
            val pasteboard = dependency(PasteboardClient)
            CoroutineScope(Dispatchers.Main).launch { pasteboard.setString(text) }
        }
        seedVoiceKeys(intent)
    }

    /** iOS `LabVoiceKeys.seed`. */
    private fun seedVoiceKeys(intent: Intent) {
        listOf("lab-openai-key" to SharedKeys.openAIAPIKey, "lab-xai-key" to SharedKeys.xaiAPIKey).forEach { (extra, key) ->
            if (!intent.hasExtra(extra)) return@forEach
            val next = intent.getStringExtra(extra)?.takeUnless { it.isEmpty() || it == "NONE" }
            val stored = shared(key)
            if (stored.value != next) stored.set(next)
        }
    }

    /** iOS `installLabPasteboardTextIfRequested`. */
    private fun pasteboardText(kind: String): String? = when (kind) {
        "log" -> (0 until 40).joinToString("\n") { index ->
            if (index == 0) "Fatal error: Index out of range in UploadQueue.drain()"
            else "$index  UploadLab  0x${(0x100f00000L + index * 0x2c).toString(16)}  UploadQueue.drain(batch:) + ${index * 12}"
        }
        // 5 MB on one line, as a minified bundle or a base64 blob pastes.
        "big" -> "Fatal error: minified bundle " + "0123456789abcdef".repeat(5 * 1024 * 1024 / 16)
        "lines" -> (0 until 25_000).joinToString("\n") { "$it  UploadQueue.drain(batch:) retrying upload" }
        "paragraph" -> "When I pick three photos and send while the agent is still working, the first one uploads but the others wait at zero percent until I leave the app and come back. "
            .repeat(5).trim()
        else -> null
    }
}
