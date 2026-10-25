package ai.opencode.feature.voice

import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.InstantSerializer
import ai.opencode.core.sharing.SharedKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * The voice-typed keys of iOS `OpenCode/Core/SharedKeys.swift` (same names, defaults and
 * semantics), declared here because their value types belong to Voice. The enums below are
 * ported from `Voice/LiveProtocol.swift`, `Voice/ServerVoiceClient.swift`, `Voice/CallVoice.swift`
 * and `Voice/VoiceTranscript.swift` with their raw values; the voice agent extends them.
 */
object VoiceSharedKeys {
    /** Voice engine for new calls. Defaults to GPT-Live, including for users upgrading from Realtime-only builds. */
    val voiceEngine = SharedKey.appStorage("voice-engine", VoiceEngine.gptLive)

    /** Voice provider when Server Voice is enabled. Defaults to OpenAI. */
    val serverVoiceProvider = SharedKey.appStorage("server-voice-provider", ServerVoiceProvider.openAI)

    /** Whether each connected server (by `persistenceID`) offers `POST /api/experimental/voice/session`. */
    val serverVoiceSupportByServer =
        SharedKey.inMemory<Map<String, ServerVoiceSupport>>("server-voice-support", emptyMap())

    /** The voice new calls speak with. A call on an engine that doesn't offer it uses the default instead. */
    val callVoice = SharedKey.appStorage("voice-call-voice", CallVoice.default)

    /** Phone-local voice transcripts keyed by `serverID:sessionID`. */
    val voiceTranscripts = SharedKey.fileStorage<Map<String, VoiceTranscriptRecord>>(
        FileDirectory.Documents,
        "voice-transcripts.json",
        emptyMap(),
    )
}

@Serializable
enum class VoiceEngine(val title: String) {
    @SerialName("gpt-live") gptLive("GPT-Live"),
    @SerialName("realtime") realtime("Realtime"),
    /** xAI's Grok Voice. It speaks the Realtime protocol. */
    @SerialName("grok") grok("Grok Voice"),
}

@Serializable
enum class ServerVoiceProvider(val title: String) {
    @SerialName("openai") openAI("OpenAI"),
    @SerialName("gemini-live") geminiLive("Gemini Live"),
}

/** Whether the connected OpenCode server offers `POST /api/experimental/voice/session`. */
@Serializable
enum class ServerVoiceSupport { unknown, checking, supported, unsupported }

@Suppress("EnumEntryName")
@Serializable
enum class CallVoice {
    alloy, ash, ballad, beacon, bossa, cedar, cinder, coral, delta, echo, gleam,
    marin, meridian, quartz, ripple, sage, shimmer, stone, tempo, verse, vesper, willow,
    altair, ara, atlas, aurora, carina, castor, celeste, cosmo, eve, helios, helix, iris, kepler,
    leo, liora, lumen, luna, lux, naksh, orion, perseus, rex, rigel, sal, sirius, ursa, zagan, zenith;

    val title: String get() = name.replaceFirstChar(Char::uppercaseChar)

    companion object {
        val default = marin
        /** xAI's default voice. */
        val grokDefault = eve
    }
}

@Serializable
data class VoiceTranscriptRecord(
    @Serializable(with = InstantSerializer::class) val endedAt: Instant,
    /** Continuity tail injected into the next call. */
    val tail: List<String>,
    /** Everything said on this phone about this session, newest last, bounded. */
    val log: List<String>,
) {
    fun appending(tail: List<String>, log: List<String>, endedAt: Instant): VoiceTranscriptRecord {
        val combined = this.log + log
        return VoiceTranscriptRecord(endedAt, tail, combined.takeLast(MAX_LOG_LINES))
    }

    companion object {
        const val MAX_LOG_LINES = 400
    }
}
