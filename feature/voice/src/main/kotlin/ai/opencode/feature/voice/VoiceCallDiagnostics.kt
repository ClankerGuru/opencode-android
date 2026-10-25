package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.log.AppLog

/** Voice-owned diagnostics recorder boundary. :app/devtools installs the two sinks
 * into its persistent recorder; closeReason is private diagnostic data, not AppLog. */
class VoiceCallDiagnostics(
    private val recordMark: (String) -> Unit = {},
    private val recordSocketEvent: (SocketEvent) -> Unit = {},
) {
    data class SocketEvent(val name: String, val domain: String, val code: Int, val closeCode: Int,
        val underlyingDomain: String?, val underlyingCode: Int?, val closeReason: String?, val errorCategory: String?)
    fun recordVoiceMark(name: String) { AppLog.voice.notice(name); recordMark(name) }
    fun recordVoiceSocket(name: String, diagnostic: RealtimeTransportDiagnostic, error: Throwable?) {
        AppLog.voice.notice("$name ${diagnostic.logSummary}")
        recordSocketEvent(SocketEvent(name, diagnostic.domain, diagnostic.code, diagnostic.closeCode,
            diagnostic.underlyingDomain, diagnostic.underlyingCode, diagnostic.closeReason,
            if (error != null) errorCategory(diagnostic) else null))
    }
    fun recordVoiceLoss(expiredReason: String?, error: Throwable?, defaultDomain: String, expiredDomain: String,
        connected: Boolean, diagnostic: RealtimeTransportDiagnostic? = null) {
        if (expiredReason != null) {
            recordVoiceSocket("voice_session_expired", RealtimeTransportDiagnostic(domain = expiredDomain, code = 0, closeReason = expiredReason), null)
            return
        }
        val detail = diagnostic ?: error?.let(RealtimeTransportDiagnostic::of) ?: RealtimeTransportDiagnostic(domain = defaultDomain, code = 0)
        if (detail.closeCode == 1001) recordVoiceSocket("voice_session_expired", detail, null)
        else recordVoiceSocket(if (connected) "voice_socket_dropped" else "voice_connect_failed", detail, error)
    }
    companion object Key : DependencyKey<VoiceCallDiagnostics> {
        override val liveValue = VoiceCallDiagnostics()
        override val testValue = VoiceCallDiagnostics()
        private fun errorCategory(diagnostic: RealtimeTransportDiagnostic): String {
            fun category(domain: String, code: Int): String = when (domain) {
                "NSURLErrorDomain" -> when (code) {
                    -1001 -> "timeout"
                    -1009, -1005, -1018, -1020 -> "offline"
                    -999 -> "cancelled"
                    else -> "transport"
                }
                "NSPOSIXErrorDomain" -> when (code) {
                    60 -> "timeout"
                    57, 54, 53, 32, 50, 51, 64, 65 -> "offline"
                    else -> "transport"
                }
                else -> "transport"
            }
            val own = category(diagnostic.domain, diagnostic.code)
            return if (own == "transport" && diagnostic.underlyingDomain != null && diagnostic.underlyingCode != null)
                category(diagnostic.underlyingDomain, diagnostic.underlyingCode) else own
        }
    }
}
