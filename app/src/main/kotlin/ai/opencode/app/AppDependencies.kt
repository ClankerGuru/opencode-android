package ai.opencode.app

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.DiagnosticsErrorCategory
import ai.opencode.core.diagnostics.DiagnosticsRecorder
import ai.opencode.core.diagnostics.LabMarks
import ai.opencode.core.tuning.DeveloperMode
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.voice.VoiceAdmissionRecorder
import ai.opencode.feature.voice.VoiceCallDiagnostics
import ai.opencode.feature.voice.VoiceCallLogUploadClient
import ai.opencode.feature.voice.VoiceDiagnostics
import ai.opencode.feature.voice.VoiceLogUploader
import ai.opencode.feature.voice.VoiceScreenActivityRegistry
import ai.opencode.server.DeveloperModeUploads
import ai.opencode.devtools.SavedVoiceLogUploads
import ai.opencode.feature.voice.AudioSessionOwner
import ai.opencode.feature.voice.VoiceCallLogClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ai.opencode.core.sound.AudioSessionIdleState
import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.Locale

/**
 * The cross-module services iOS reaches directly (it's one module) and Android installs at the
 * root because they live above the module that calls them: Voice's admitted sends go into the
 * composer's outbox, its logs to Developer Mode's uploader, its marks to the diagnostics
 * recorder, and its screen hold to the resumed activity.
 */
internal object AppDependencies {
    /** Detached uploads (iOS `Task.detached(priority: .utility)`). */
    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun install(application: Application) {
        val registry = VoiceScreenActivityRegistry()
        DependencyValues.installRoot {
            // Sound Effects borrow the idle audio session through voice's owner (iOS
            // `SoundEffectsPlayback` takes `AudioSessionOwner.shared`): a call, dictation or
            // media holder stops a sound before it activates, and none plays while one is held.
            this[AudioSessionIdleState] = object : AudioSessionIdleState {
                override fun <T> withIdleState(body: (isIdle: Boolean) -> T): T =
                    dependency(AudioSessionOwner).withIdleState(body)

                override fun onHolderActivating(stop: () -> Unit) =
                    dependency(AudioSessionOwner).onHolderActivating(stop)
            }
            // iOS `VoiceFeature+Admission`: `PromptOutbox.recordAdmitted` with the send voice made.
            this[VoiceAdmissionRecorder] = VoiceAdmissionRecorder { prompt, sessionID, serverKey ->
                dependency(PromptOutbox).recordAdmitted(
                    PendingPrompt(
                        messageID = prompt.messageID,
                        text = prompt.text,
                        attachments = emptyList(),
                        files = prompt.files.map { ComposerFileAttachment(uploaded = it) },
                        ownsRun = prompt.ownsRun,
                        delivery = prompt.delivery,
                        requestEpoch = prompt.requestEpoch,
                    ),
                    sessionID,
                    serverKey,
                )
            }
            // iOS `DeveloperMode` as the delegator's `send_call_log` uses it.
            this[VoiceLogUploader] = VoiceLogUploader(
                isEnabled = { DeveloperMode.isEnabled },
                upload = { log -> DeveloperModeUploads.uploadVoiceLog(log.id, log.boundedJSON()) },
                url = { id -> "${DeveloperModeUploads.webURL()}/${id.toString().uppercase(Locale.ROOT)}" },
            )
            // A finished call's log, uploaded when Developer Mode is on (the recorder gates it).
            this[VoiceCallLogUploadClient] = VoiceCallLogUploadClient { log ->
                DeveloperModeUploads.uploadVoiceLog(log.id, log.boundedJSON())
            }
            // iOS `developerModeChanged(true)`: every saved call log goes up, detached.
            this[SavedVoiceLogUploads] = SavedVoiceLogUploads {
                uploadScope.launch {
                    for (log in dependency(VoiceCallLogClient).list()) DeveloperModeUploads.uploadVoiceLog(log.id, log.boundedJSON())
                }
            }
            // iOS `DiagnosticsClient.voice` and `LabMarks.mark`.
            this[VoiceDiagnostics] = VoiceDiagnostics(
                voice = { name, sessionID, detail, excerpt -> DiagnosticsRecorder.shared.recordVoice(name, sessionID, detail, excerpt) },
                labMark = { name -> LabMarks.mark(name) },
            )
            // iOS `DiagnosticsRecorder.recordVoiceMark` / `recordVoiceSocket`.
            this[VoiceCallDiagnostics] = VoiceCallDiagnostics(
                recordMark = { name -> DiagnosticsRecorder.shared.recordMark(name, null) },
                recordSocketEvent = { event ->
                    DiagnosticsRecorder.shared.recordVoiceSocketEvent(
                        name = event.name,
                        domain = event.domain,
                        code = event.code,
                        closeCode = event.closeCode,
                        underlyingDomain = event.underlyingDomain,
                        underlyingCode = event.underlyingCode,
                        closeReason = event.closeReason,
                        errorCategory = event.errorCategory?.let(::errorCategory),
                    )
                },
            )
            this[VoiceScreenActivityRegistry] = registry
        }
        // The call's screen hold goes to whichever activity is in front.
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) = registry.attach(activity)
                override fun onActivityPaused(activity: Activity) = registry.detach(activity)
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    /** Voice's category description (`unauthorized`, `server(500)`, …) as the recorder's. */
    private fun errorCategory(description: String): DiagnosticsErrorCategory = when (description) {
        "unauthorized" -> DiagnosticsErrorCategory.Unauthorized
        "notFound" -> DiagnosticsErrorCategory.NotFound
        "timeout" -> DiagnosticsErrorCategory.Timeout
        "offline" -> DiagnosticsErrorCategory.Offline
        "cancelled" -> DiagnosticsErrorCategory.Cancelled
        else -> description.removePrefix("server(").removeSuffix(")").toIntOrNull()?.let { DiagnosticsErrorCategory.Server(it) }
            ?: DiagnosticsErrorCategory.Transport
    }
}
