package ai.opencode.feature.voice

/*
 * iOS `RealtimeClient.swift`. Lines 1–126 (the preflight) live in `RealtimePreflight.kt`.
 * TODO(voice transport): `RealtimeConnection`, `RealtimeClient`, the WebSocket and the rest of
 * the file. Only `RealtimeClientError` is here so far: the call reducer ends a call as dropped
 * on `RealtimeClientError.Dropped` (`VoiceFeatureStart.kt`).
 */

/** Why a Realtime connection ended (iOS `RealtimeClientError`); [message] is iOS's `errorDescription`. */
sealed class RealtimeClientError(override val message: String) : Exception(message), CarriesRealtimeTransportDiagnostic {
    data object InvalidURL : RealtimeClientError("The voice service address is invalid.")
    data class Closed(val reason: String) : RealtimeClientError(reason)

    /** The service ended the session: its time limit, or a going-away close. */
    data class SessionEnded(val diagnostic: RealtimeTransportDiagnostic) : RealtimeClientError("Call ended")

    /** The socket failed or closed under the call. */
    data class TransportLost(val diagnostic: RealtimeTransportDiagnostic) : RealtimeClientError("The voice connection was lost.")

    /** Reconnecting failed; the call is over. */
    data object Dropped : RealtimeClientError("Call ended")

    override val transportDiagnostic: RealtimeTransportDiagnostic?
        get() = when (this) {
            is SessionEnded -> diagnostic
            is TransportLost -> diagnostic
            InvalidURL, is Closed, Dropped -> null
        }
}
