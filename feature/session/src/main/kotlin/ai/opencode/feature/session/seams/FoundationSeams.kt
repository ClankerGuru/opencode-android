package ai.opencode.feature.session.seams

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.feature.media.VoiceViewingPage
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeSession
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// Stand-ins for :feature:voice / :feature:system types `SessionFeature`'s State/Action/body
// reference that this module doesn't take from those areas yet. Each carries only what Session
// uses. Reducer seams return `Reduced(state)` so composition works.

// region Voice

/** SEAM: real type belongs to :feature:voice (iOS `Voice/ServerVoiceClient.swift`); delete when that area merges. */
@Serializable(with = VoiceServerChoiceSerializer::class)
sealed interface VoiceServerChoice {
    /** The server the window is connected to, as before there was a choice. */
    data object Connected : VoiceServerChoice
    /** None: calls use this phone's own OpenAI or xAI key. */
    data object Phone : VoiceServerChoice
    data class Saved(val serverID: String) : VoiceServerChoice

    val rawValue: String
        get() = when (this) {
            Connected -> ""
            Phone -> "phone"
            is Saved -> serverID
        }

    /** The saved server this choice names, if it's still saved. */
    fun savedServer(servers: List<OpenCodeServer>): OpenCodeServer? {
        val saved = this as? Saved ?: return null
        return servers.firstOrNull { it.persistenceID == saved.serverID }
    }

    companion object {
        fun of(rawValue: String): VoiceServerChoice = when (rawValue) {
            "" -> Connected
            "phone" -> Phone
            else -> Saved(rawValue)
        }

        /** Settings › Voice › Voice Server. The connected server by default. */
        val key: SharedKey<VoiceServerChoice> get() = VoiceServerChoiceKey.key
    }
}

/**
 * Outside the companion: building the key resolves `VoiceServerChoice.serializer()` through the
 * companion, which would still be initialising (ExceptionInInitializerError on first use).
 */
private object VoiceServerChoiceKey {
    val key: SharedKey<VoiceServerChoice> = SharedKey.appStorage("voice-server", VoiceServerChoice.Connected)
}

internal object VoiceServerChoiceSerializer : KSerializer<VoiceServerChoice> {
    override val descriptor = PrimitiveSerialDescriptor("VoiceServerChoice", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: VoiceServerChoice) = encoder.encodeString(value.rawValue)
    override fun deserialize(decoder: Decoder): VoiceServerChoice = VoiceServerChoice.of(decoder.decodeString())
}

/** SEAM: real type belongs to :feature:voice (iOS `Voice/VoiceRoute.swift`); delete when that area merges. */
sealed interface VoiceRoute {
    data class Server(val provider: ServerVoiceProvider, val engine: VoiceEngine?) : VoiceRoute
    data class PhoneKey(val engine: VoiceEngine) : VoiceRoute
    data class Unavailable(val serverUnsupported: Boolean, val engine: VoiceEngine?) : VoiceRoute

    val isAvailable: Boolean get() = this !is Unavailable

    companion object {
        fun resolve(
            selectedEngine: VoiceEngine,
            serverVoiceEnabled: Boolean,
            serverVoiceProvider: ServerVoiceProvider,
            serverVoiceSupport: ServerVoiceSupport,
            hasOpenAIKey: Boolean,
            hasXAIKey: Boolean,
        ): VoiceRoute {
            // Server Voice never owns Grok, including when Gemini is selected on the server.
            if (selectedEngine == VoiceEngine.grok) {
                return if (hasXAIKey) PhoneKey(VoiceEngine.grok) else Unavailable(false, VoiceEngine.grok)
            }
            if (serverVoiceEnabled && serverVoiceProvider == ServerVoiceProvider.geminiLive) {
                return if (serverVoiceSupport == ServerVoiceSupport.unsupported) Unavailable(true, null)
                else Server(ServerVoiceProvider.geminiLive, null)
            }
            if (serverVoiceEnabled && serverVoiceSupport != ServerVoiceSupport.unsupported) {
                return Server(
                    ServerVoiceProvider.openAI,
                    if (selectedEngine == VoiceEngine.gptLive) VoiceEngine.gptLive else VoiceEngine.realtime,
                )
            }
            // Preserve the documented fallback only after a confirmed unsupported route.
            return if (hasOpenAIKey) PhoneKey(selectedEngine) else Unavailable(false, selectedEngine)
        }
    }
}

/** SEAM: real type belongs to :feature:voice (iOS `Voice/VoiceRoute.swift` `VoiceBroker`); delete when that area merges. */
class VoiceBroker(serverVoice: Boolean, choice: VoiceServerChoice, connected: OpenCodeServer?, saved: () -> List<OpenCodeServer>) {
    /** Server Voice brokers new calls: it's on, and This Phone isn't chosen. */
    val isEnabled: Boolean = serverVoice && choice != VoiceServerChoice.Phone

    /** The chosen saved server, else the connected one. Null while nothing brokers. */
    val server: OpenCodeServer? = if (isEnabled) choice.savedServer(saved()) ?: connected else null

    fun support(supportByServer: Map<String, ServerVoiceSupport>): ServerVoiceSupport =
        server?.let { supportByServer[it.persistenceID] } ?: ServerVoiceSupport.unknown
}

/** SEAM: real type belongs to :feature:voice (iOS `Voice/VoiceFeature.swift`); delete when that area merges. */
data class VoiceWorkTarget(val server: OpenCodeServer, val sessionID: String?, val directory: String)

/**
 * SEAM: real type belongs to :feature:voice (iOS `Voice/VoiceFeature.swift`); delete when that area
 * merges. Only the members `SessionFeature` reads and writes.
 */
class VoiceFeature : Reducer<VoiceFeature.State, VoiceFeature.Action> {
    data class State(
        val target: VoiceWorkTarget,
        val sessionTitle: String,
        /** The page open over the session in the in-app browser. */
        val viewing: VoiceViewingPage? = null,
        /** The session is waiting on a native approval or answer. */
        val targetNeedsInput: Boolean = false,
        val phase: Phase = Phase.Connecting,
        /** The model the call's next delegation applies. */
        val pendingModel: OpenCodeModelRef? = null,
        val admittedPromptIDs: List<String> = emptyList(),
        val compactions: Map<String, String> = emptyMap(),
        val replyWatch: ReplyWatch = ReplyWatch(),
        /** The session a request made before the call's draft lands creates. */
        val draftSessionID: String? = null,
        val serverVoiceSupport: ServerVoiceSupport? = null,
    ) {
        sealed interface Phase {
            data object Connecting : Phase
            data object Live : Phase
            data class EndingAfterGoodbye(val spoken: Boolean) : Phase
            data object Ended : Phase
            data class Failed(val message: String) : Phase
            data object Dropped : Phase

            /** A card above the composer replaces the call bar. */
            val showsCard: Boolean get() = this is Failed || this == Dropped
        }

        /** The newest finished compaction the session screen has seen. */
        data class CompactionMark(val sessionID: String, val compactionID: String)

        data class ReplyWatch(val activeSessionIDs: Set<String> = emptySet())

        /** A call vouches for its session from admission until the last delegated prompt answers. */
        val hasAdmittedWork: Boolean get() = admittedPromptIDs.isNotEmpty()

        companion object {
            fun session(server: OpenCodeServer, session: OpenCodeSession) = State(
                target = VoiceWorkTarget(server, session.id, session.directory),
                sessionTitle = usableTitle(session.title) ?: "New Session",
            )

            fun draft(server: OpenCodeServer, draftDirectory: String, model: OpenCodeModelRef?) = State(
                target = VoiceWorkTarget(server, null, draftDirectory),
                sessionTitle = "New Session",
                pendingModel = model,
            )

            fun usableTitle(title: String): String? {
                val trimmed = title.trim()
                if (trimmed.isEmpty() || trimmed.equals("Untitled session", ignoreCase = true)) return null
                return trimmed
            }

            /** The user's saved servers, most recent first. */
            val savedServers: List<OpenCodeServer>
                get() = shared(ServerSharedKeys.savedServers).value + listOfNotNull(shared(ServerSharedKeys.savedServer).value)
        }
    }

    sealed interface Action {
        data class Delegate(val delegate: VoiceFeature.Delegate) : Action
        data object Start : Action
        data object EndTapped : Action
        data class AtEarChanged(val atEar: Boolean) : Action
        data class CompactionObserved(val mark: State.CompactionMark) : Action
        data class ViewingChanged(val page: VoiceViewingPage?) : Action
        data class SessionRenamed(val title: String) : Action
    }

    sealed interface Delegate {
        data class SessionCreated(val session: OpenCodeSession) : Delegate
        data class PromptAdmitted(val sessionID: String) : Delegate
        data class OpenSession(val session: OpenCodeSession) : Delegate
        data class SetPinned(val session: OpenCodeSession, val pinned: Boolean) : Delegate
        data class OpenDraft(val directory: String?) : Delegate
        data class ModelSwitched(val sessionID: String?, val model: OpenCodeModelRef) : Delegate
        data class PendingModelApplied(val sessionID: String, val model: OpenCodeModelRef) : Delegate
        data class PickWaiting(val sessionID: String, val model: OpenCodeModelRef) : Delegate
        data object Ended : Delegate
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = Reduced(state)
}

/** SEAM: real constant belongs to :feature:voice (iOS `Voice/DotScopeSimulation.swift`); delete when that area merges. */
object DotScopeSimulation {
    /** Seconds the scope sweeps out after a call ends. */
    const val exitDuration: Double = 0.55
}

// endregion

// region System clients

/** SEAM: real client belongs to :feature:system (iOS `SceneWindowClient`); delete when that area merges. */
class SceneWindowClient {
    companion object Key : DependencyKey<SceneWindowClient> {
        override val liveValue = SceneWindowClient()
        override val testValue = SceneWindowClient()
    }
}

// endregion

