package ai.opencode.feature.voice

import kotlinx.coroutines.flow.Flow

// The coordinator wave fills the rest of this file.
interface VoiceSocketConnection<ServerEvent, Command> {
    val events: Flow<ServerEvent>
    val send: suspend (Command) -> Unit
    val close: suspend () -> Unit

    /** Android WebRTC owns the hardware. Activate this INSTEAD of VoiceAudioClient.start.
     * Leave standbys inactive. Install one VoiceRTCAudioContext dependency per call
     * to retain its engine/cues across RTC rotation; startAudio moves hardware to
     * the replacement, and the coordinator stops the returned audio at call end.
     * Without that context the connection owns/stops its own engine on close.
     * Null means WebSocket: use VoiceAudioClient and forward capture/output normally. */
    val startAudio: (suspend (atEar: Boolean) -> VoiceAudioSession)? get() = null

    suspend fun sendControl(command: Command, named: String) {
        try { send(command) } catch (error: Exception) {
            VoiceControlSend.recordFailure(named, error)
            throw error
        }
    }
    suspend fun sendControlIfPossible(command: Command, named: String): Boolean =
        try { sendControl(command, named); true } catch (_: Exception) { false }
}
