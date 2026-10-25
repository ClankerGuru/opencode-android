package ai.opencode.feature.voice

import ai.opencode.server.OpenCodeServer

/** The only precedence table for a new call. Never contains a phone key. */
sealed interface VoiceRoute {
    data class Server(val server: ServerRoute) : VoiceRoute
    data class PhoneKey(override val engine: VoiceEngine) : VoiceRoute
    data class Unavailable(val reason: Reason) : VoiceRoute

    /** iOS `VoiceRoute.OpenAI`. */
    enum class OpenAI {
        gptLive, realtime;

        val engine: VoiceEngine get() = if (this == gptLive) VoiceEngine.gptLive else VoiceEngine.realtime
    }

    /** iOS `VoiceRoute.Server` (named `ServerRoute` here: Kotlin can't nest it beside the `Server` case). */
    sealed interface ServerRoute {
        data class OpenAI(val model: VoiceRoute.OpenAI) : ServerRoute
        data object GeminiLive : ServerRoute
    }

    sealed interface Reason {
        data class MissingPhoneKey(val engine: VoiceEngine) : Reason
        data object ServerUnsupported : Reason

        val message: String
            get() = when (this) {
                is MissingPhoneKey -> "Add your ${engine.provider.displayName} API key in Settings to use Voice."
                ServerUnsupported -> ServerVoiceError.unsupportedServerNotice
            }
    }

    val isAvailable: Boolean get() = this !is Unavailable

    val isServer: Boolean get() = this is Server

    val warmsPhoneConnection: Boolean get() = this == PhoneKey(VoiceEngine.gptLive)
    val usesBrokerDelegator: Boolean get() = this == serverOpenAI(OpenAI.gptLive)
    val sendsContextDelta: Boolean get() = engine == VoiceEngine.gptLive || this == serverGeminiLive

    /** Gemini is not an OpenAI engine, even though it shares the attached-session tools. */
    val engine: VoiceEngine?
        get() = when (this) {
            is PhoneKey -> engine
            is Server -> when (val route = server) {
                is ServerRoute.OpenAI -> route.model.engine
                ServerRoute.GeminiLive -> null
            }
            is Unavailable -> null
        }

    val logEngine: String get() = engine?.rawValue ?: if (this == serverGeminiLive) "gemini-live" else "unavailable"
    val provider: ServerVoiceProvider get() = if (this == serverGeminiLive) ServerVoiceProvider.geminiLive else ServerVoiceProvider.openAI
    val modelID: String get() = engine?.modelID ?: GeminiLiveSessionConfig.defaultModel

    val keySource: VoiceKeySource
        get() = when (this) {
            is Server -> when (server) {
                is ServerRoute.OpenAI -> VoiceKeySource.Server(providerName = "OpenAI")
                ServerRoute.GeminiLive -> VoiceKeySource.Server(providerName = "Gemini Live")
            }
            is Unavailable -> when (val reason = reason) {
                Reason.ServerUnsupported -> VoiceKeySource.Server(providerName = "Gemini Live")
                is Reason.MissingPhoneKey -> VoiceKeySource.Phone(reason.engine.provider)
            }
            is PhoneKey -> VoiceKeySource.Phone(engine.provider)
        }

    fun sessionConfig(
        voice: CallVoice,
        context: VoiceInstructions.Context,
        isAtEar: Boolean,
        server: OpenCodeServer,
        delegation: LiveDelegation = LiveDelegation.client,
    ): RealtimeSessionConfig = RealtimeSessionConfig(
        engine = engine,
        model = modelID,
        voice = voice,
        instructions = VoiceInstructions.build(context, route = this),
        tools = if (engine == VoiceEngine.gptLive) emptyList() else VoiceInstructions.tools,
        isAtEar = isAtEar,
        serverVoice = if (isServer) server else null,
        provider = provider,
        liveDelegation = if (usesBrokerDelegator) delegation else LiveDelegation.client,
    )

    companion object {
        /** iOS `.server(.openAI(model))`. */
        fun serverOpenAI(model: OpenAI): VoiceRoute = Server(ServerRoute.OpenAI(model))

        /** iOS `.server(.geminiLive)`. */
        val serverGeminiLive: VoiceRoute = Server(ServerRoute.GeminiLive)

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
                return if (hasXAIKey) PhoneKey(VoiceEngine.grok) else Unavailable(Reason.MissingPhoneKey(VoiceEngine.grok))
            }
            if (serverVoiceEnabled && serverVoiceProvider == ServerVoiceProvider.geminiLive) {
                return if (serverVoiceSupport == ServerVoiceSupport.unsupported) Unavailable(Reason.ServerUnsupported) else serverGeminiLive
            }
            if (serverVoiceEnabled && serverVoiceSupport != ServerVoiceSupport.unsupported) {
                return serverOpenAI(if (selectedEngine == VoiceEngine.gptLive) OpenAI.gptLive else OpenAI.realtime)
            }
            // Preserve the documented fallback only after a confirmed unsupported route.
            return if (hasOpenAIKey) PhoneKey(selectedEngine) else Unavailable(Reason.MissingPhoneKey(selectedEngine))
        }
    }
}

/**
 * Who pays for a call (iOS `VoiceKeySource`, `Settings/VoiceKeySource.swift`), moved down to
 * Voice because [VoiceRoute.keySource] returns it and Settings sits above Voice. Settings'
 * `VoiceKeyStatus` stays in Settings.
 */
sealed interface VoiceKeySource {
    data class Phone(val provider: VoiceProvider) : VoiceKeySource
    data class Server(val providerName: String) : VoiceKeySource

    /**
     * The server's OpenCode Console connection: OpenCode hosts the call and
     * bills the connection's workspace.
     */
    data object OpenCode : VoiceKeySource

    val rowTitle: String
        get() = when (this) {
            is Phone -> provider.keyRowTitle
            is Server, OpenCode -> "Key on Your Server"
        }

    /** Settings › Voice's footer: whose key, or which workspace, pays for calls. */
    val footer: String
        get() = when (this) {
            is Phone -> "Calls use your own OpenAI or xAI key."
            is Server -> "Calls use your server’s $providerName key."
            OpenCode -> "Calls are billed to your server’s OpenCode Console workspace."
        }

    val isPhoneKey: Boolean get() = this is Phone
}

/**
 * Which server brokers a new call's Server Voice session (Settings › Voice ›
 * Voice Server), for calls on `connected`. Settings, Home, the session and a
 * call's start all resolve it here, and `resolve` takes its inputs from it.
 * Only the broker moves: a call's session, context and tools stay on its own server.
 */
class VoiceBroker(serverVoice: Boolean, choice: VoiceServerChoice, connected: OpenCodeServer?, saved: () -> List<OpenCodeServer>) {
    /** Server Voice brokers new calls: it's on, and This iPhone isn't chosen. */
    val isEnabled: Boolean = serverVoice && choice != VoiceServerChoice.Phone

    /**
     * The chosen saved server, else the connected one (by default, or once the
     * chosen one is no longer saved). Null while nothing brokers.
     */
    val server: OpenCodeServer? = if (isEnabled) choice.savedServer(saved()) ?: connected else null

    /** What's known of whether the broker offers `POST /api/experimental/voice/session`. */
    fun support(supportByServer: Map<String, ServerVoiceSupport>): ServerVoiceSupport =
        server?.let { supportByServer[it.persistenceID] } ?: ServerVoiceSupport.unknown

    override fun equals(other: Any?): Boolean = other is VoiceBroker && other.isEnabled == isEnabled && other.server == server
    override fun hashCode(): Int = isEnabled.hashCode() * 31 + server.hashCode()
    override fun toString(): String = "VoiceBroker(isEnabled=$isEnabled, server=${server?.persistenceID?.let { "set" }})"
}
