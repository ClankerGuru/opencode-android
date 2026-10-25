package ai.opencode.feature.session.seams

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.callOwnsAudio
import ai.opencode.feature.session.composerWithoutDictation
import ai.opencode.feature.session.draftState
import ai.opencode.feature.session.hasLiveDictation
import ai.opencode.feature.session.hasPendingRequests
import ai.opencode.feature.session.parkedComposer
import ai.opencode.feature.session.sessionComposer
import ai.opencode.feature.session.pendingAttachments
import ai.opencode.feature.session.pendingFiles
import ai.opencode.feature.session.pendingLocation
import ai.opencode.feature.session.undoSnapshot
import ai.opencode.feature.session.withDraft
import ai.opencode.feature.session.withDraftState
import ai.opencode.feature.session.withParkedComposer
import ai.opencode.feature.session.withUndoSnapshot
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeAutocompleteAgent
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeFileReference
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.util.UUID

// Stand-ins for types `SessionFeature`'s State/Action/body reference whose real ports belong to
// other areas (or to Composer/Transcript/Voice/Browser files that extend `SessionFeature.State`
// on iOS and so must eventually live in this module). Each carries only what Session uses.
// Reducer seams return `Reduced(state)` so composition works.

// region Experimental features (Settings)

/**
 * SEAM: real type belongs to :feature:settings (iOS `OpenCode/Settings/ExperimentalFeatures.swift`);
 * it must move down to :core so Session can read it. Delete when that area merges.
 */
enum class ExperimentalFeature(val rawValue: String) {
    composerAutocomplete("composer-autocomplete"),
    plusMenuSheet("plus-menu-sheet"),
    agentSelection("agent-selection"),
    hideModels("hide-models"),
    callTray("call-tray"),
    floatingCall("floating-call"),
    livingOrb("living-orb"),
    newMessagesDivider("new-messages-divider"),
    sinceYouLeft("since-you-left"),
    imageLinkPreviews("image-link-previews"),
    projectIcons("project-icons"),
    sessionSwitcher("session-switcher"),
    serverTitleSwitch("server-title-switch"),
    serverDetails("server-details"),
    inAppBrowser("in-app-browser"),
    serverVoice("server-voice"),
    backgroundCallIntent("background-call-intent");

    /** Whether this experiment is on (iOS also honours lab overrides in DEBUG). */
    fun isActive(stored: Boolean): Boolean = stored

    companion object {
        private val keys = HashMap<ExperimentalFeature, SharedKey<Boolean>>()

        /** iOS `@Shared(.experimental(feature))`: `experimental-<raw>` in app storage, off by default. */
        fun key(feature: ExperimentalFeature): SharedKey<Boolean> = synchronized(keys) {
            keys.getOrPut(feature) { SharedKey.appStorage("experimental-${feature.rawValue}", false) }
        }
    }
}

/** SEAM: real type belongs to :feature:settings (iOS `ExperimentalFeatures` client); delete when that area merges. */
class ExperimentalFeatures(val isEnabled: (ExperimentalFeature) -> Boolean) {
    operator fun invoke(feature: ExperimentalFeature): Boolean = isEnabled(feature)

    companion object Key : DependencyKey<ExperimentalFeatures> {
        override val liveValue: ExperimentalFeatures = ExperimentalFeatures { shared(ExperimentalFeature.key(it)).value }
        override val testValue: ExperimentalFeatures = ExperimentalFeatures { false }
    }
}

// endregion

// region Models (Composer/ModelPicker)

/** SEAM: real type belongs to :feature:composer (iOS `Composer/ModelPicker/ModelVisibility.swift`); delete when that area merges. */
@Serializable
data class ModelVisibility(
    val mode: Mode = Mode.hide,
    val hiddenProviderIDs: Set<String> = emptySet(),
    val hiddenModelIDs: Set<String> = emptySet(),
    val shownModelIDs: Set<String> = emptySet(),
) {
    @Serializable
    enum class Mode { hide, onlyShow }

    private fun isVisible(model: OpenCodeModel, liveShownIDs: Set<String>): Boolean {
        if (model.providerID in hiddenProviderIDs) return false
        return when (mode) {
            Mode.hide -> model.id !in hiddenModelIDs
            Mode.onlyShow -> liveShownIDs.isEmpty() || model.id in liveShownIDs
        }
    }

    private fun liveShownIDs(catalog: List<OpenCodeModel>): Set<String> = shownModelIDs.intersect(catalog.map { it.id }.toSet())

    /** [models] as the picker lists them, keeping the current model so its checkmark never vanishes. */
    fun visible(models: List<OpenCodeModel>, keeping: String?): List<OpenCodeModel> {
        val live = liveShownIDs(models)
        return models.filter { isVisible(it, live) || it.id == keeping }
    }

    companion object {
        /** Each saved server's model visibility, keyed by its persistence ID. */
        val modelVisibilityByServer =
            SharedKey.fileStorage<Map<String, ModelVisibility>>(FileDirectory.Documents, "model-visibility.json", emptyMap())
    }
}

/**
 * SEAM: real type belongs to :feature:composer (iOS `Composer/ModelPicker/ModelPickerSections.swift`
 * `ModelSearch`); delete when that area merges. Stand-in: a plain case-insensitive name/ID filter.
 */
object ModelSearch {
    fun rank(models: List<OpenCodeModel>, query: String, favorites: Set<String> = emptySet()): List<OpenCodeModel> {
        val terms = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return models
        @Suppress("UNUSED_VARIABLE") val unused = favorites
        return models.filter { model ->
            val haystack = "${model.name} ${model.id}".lowercase()
            terms.all { haystack.contains(it) }
        }
    }
}

/** SEAM: real keys belong to :feature:composer (iOS `Composer/AgentPicker.swift`); delete when that area merges. */
object AgentPickerKeys {
    /** The agent new sessions start with (an agent ID); empty is the server's default. */
    val defaultAgentID = SharedKey.appStorage("default-agent", "")

    /** The agent picked in each session (session ID → agent ID). */
    val sessionAgents = SharedKey.fileStorage<Map<String, String>>(FileDirectory.Documents, "session-agents.json", emptyMap())

    /** The primary agents last listed, for Settings' Default Agent. */
    val knownPrimaryAgents =
        SharedKey.fileStorage<List<OpenCodeAutocompleteAgent>>(FileDirectory.Documents, "primary-agents.json", emptyList())
}

// endregion

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
        val key: SharedKey<VoiceServerChoice> = SharedKey.appStorage("voice-server", Connected)
    }
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

/** SEAM: real type belongs to :feature:media (iOS `Browser/BrowserFeature.swift`); delete when that area merges. */
data class VoiceViewingPage(val url: String, val title: String, val isOpen: Boolean)

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

// region Browser and links (Media)

/** SEAM: real type belongs to :feature:media (iOS `Browser/BrowserFeature.swift`); delete when that area merges. */
data class PageShareRequest(val pageID: UUID, val url: String, val title: String, val includesScreenshot: Boolean)

/** SEAM: real type belongs to :feature:media (iOS `Browser/BrowserFeature.swift`); delete when that area merges. */
class BrowserFeature : Reducer<BrowserFeature.State, BrowserFeature.Action> {
    data class State(
        val pageID: UUID? = null,
        val openedURL: String? = null,
        val url: String? = null,
        val title: String = "",
        val peekMessageID: String? = null,
        /** The Ask composer over the bar, and what's typed in it. */
        val isAsking: Boolean = false,
        val askDraft: String = "",
    ) {
        val isOpen: Boolean get() = pageID != null

        /** The title the bar shows: the page's, or its host while loading. */
        val displayTitle: String
            get() = title.trim().ifEmpty {
                val host = url?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: ""
                if (host.startsWith("www.")) host.drop(4) else host
            }

        /** What a question from here asks the page reader for. */
        val shareRequest: PageShareRequest?
            get() {
                val pageID = pageID ?: return null
                val url = url ?: return null
                return PageShareRequest(pageID, url, displayTitle, includesScreenshot = false)
            }

        val viewing: VoiceViewingPage?
            get() {
                val url = url ?: return null
                if (pageID == null) return null
                return VoiceViewingPage(url, title.trim().ifEmpty { url }, isOpen = true)
            }
    }

    sealed interface Action {
        data class Open(val url: String) : Action
        /** Ask about this page: the question goes with the page attached. */
        data object AskSent : Action
        /** A choice from a link's menu inside the page. */
        data class LinkMenu(val action: LinkMenuAction, val url: String) : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = Reduced(state)
}

/** SEAM: real type belongs to :feature:media (iOS `Browser/LinkMenu.swift`); delete when that area merges. */
enum class LinkMenuAction(val title: String, val systemImage: String) {
    openInAppBrowser("Open in App Browser", "globe"),
    /** iOS "Open in Safari": the system's default browser on Android. */
    openInSafari("Open in Browser", "safari"),
    openInApp("Open in App", "arrow.up.forward.app"),
    copy("Copy Link", "doc.on.doc"),
    share("Share…", "square.and.arrow.up"),
    /** A session ID in a reply (`SessionLink`): TalkBack's open action. */
    openSession("Open Session", "arrow.up.right.square"),
    copySessionID("Copy ID", "doc.on.doc"),
}

/** SEAM: real type belongs to :feature:media (iOS `Browser/LinkMenu.swift`); delete when that area merges. */
data class LinkProbe(val id: UUID, val url: String, val sessionID: String?)

/** SEAM: real type belongs to :feature:media (iOS `Browser/LinkMenu.swift` `UniversalLinkClient`); delete when that area merges. */
class UniversalLinkClient(val open: suspend (String) -> Boolean) {
    companion object Key : DependencyKey<UniversalLinkClient> {
        override val liveValue = UniversalLinkClient { false }
        override val testValue = UniversalLinkClient { false }
    }
}

/** SEAM: real type belongs to :feature:transcript (iOS `Transcript/SessionLinks.swift`); delete when that area merges. */
sealed interface SessionLinkTarget {
    /** Asked for, not answered yet: shows the short ID. */
    data object Resolving : SessionLinkTarget
    data class Session(val title: String, val parentID: String?) : SessionLinkTarget
    /** No such session on this server: stays the short ID. */
    data object Missing : SessionLinkTarget

    companion object {
        fun of(session: OpenCodeSession): SessionLinkTarget = Session(session.title, session.parentID)
    }
}

// endregion

// region System clients

/** SEAM: real client belongs to :core/:feature:system (iOS `OpenURLClient`); delete when that area merges. */
class OpenURLClient(val open: suspend (String) -> Boolean) {
    companion object Key : DependencyKey<OpenURLClient> {
        override val liveValue = OpenURLClient { false }
        override val testValue = OpenURLClient { false }
    }
}

/** SEAM: real client belongs to :feature:media (iOS `PageReaderClient`); delete when that area merges. */
class PageReaderClient(
    /** Forgets a closed page's web view and what was read from it. */
    val discard: suspend (UUID) -> Unit = {},
) {
    companion object Key : DependencyKey<PageReaderClient> {
        override val liveValue = PageReaderClient()
        override val testValue = PageReaderClient()
    }
}

/** SEAM: real client belongs to :feature:system (iOS `SceneWindowClient`); delete when that area merges. */
class SceneWindowClient {
    companion object Key : DependencyKey<SceneWindowClient> {
        override val liveValue = SceneWindowClient()
        override val testValue = SceneWindowClient()
    }
}

/** SEAM: real client belongs to :feature:transcript (iOS `MarkdownStream` client); delete when that area merges. */
class MarkdownStreamClient {
    companion object Key : DependencyKey<MarkdownStreamClient> {
        override val liveValue = MarkdownStreamClient()
        override val testValue = MarkdownStreamClient()
    }
}

/** SEAM: real client belongs to :feature:composer (iOS `Composer/ComposerPersistence.swift`); delete when that area merges. */
class ComposerPersistenceClient {
    companion object Key : DependencyKey<ComposerPersistenceClient> {
        override val liveValue = ComposerPersistenceClient()
        override val testValue = ComposerPersistenceClient()
    }
}

/** SEAM: real client belongs to :feature:composer (iOS `Composer/DictationClient.swift`); delete when that area merges. */
class DictationClient(
    /** Starts the mic for a new press, from the touch; returns the press's token. */
    val begin: () -> Int = { 0 },
    /** The press's whole-session transcripts as they grow; ends when the recording does. */
    val start: suspend (press: Int) -> kotlinx.coroutines.flow.Flow<String> = { kotlinx.coroutines.flow.emptyFlow() },
    /** Stops capture, letting the engine finalize its last words. */
    val stop: suspend (press: Int) -> Unit = {},
    /** Tears the recording down at once, dropping audio not yet transcribed. */
    val cancel: suspend (press: Int) -> Unit = {},
    /** How [press] ends, for the audio hold after it. */
    val ends: (press: Int, end: DictationEnd) -> Unit = { _, _ -> },
    /** Ends a hold kept after a recording when [DictationHoldRelease] applies to it. */
    val releaseHold: suspend (reason: DictationHoldRelease) -> Unit = {},
    /** Dictation still holds the audio session (recording, finalizing, or not yet released). */
    val holdsAudio: () -> Boolean = { false },
) {
    companion object Key : DependencyKey<DictationClient> {
        override val liveValue = DictationClient()
        override val testValue = DictationClient()
    }
}

/** SEAM: real type belongs to the Diagnostics port (iOS `Diagnostics/LabMarks.swift`); delete when it lands. */
object LabMarks {
    fun mark(name: String, sessionID: String? = null) {
        AppLog.perf.debug { "$name session=${sessionID ?: "-"}" }
    }
}

// endregion

// region Transcript

/**
 * SEAM: real member belongs to :feature:transcript (iOS `Transcript/TranscriptEntry.swift`
 * `entryStart(at:in:)`); delete when that area merges. Where a window of [items] starting at
 * [index] may start without splitting a tool group or a send's rows.
 */
fun TranscriptEntry.entryStart(index: Int, items: List<OpenCodeTranscriptItem>): Int {
    fun kind(item: OpenCodeTranscriptItem): String? = when (item) {
        is OpenCodeTranscriptItem.Tool -> {
            val call = item.call
            val streaming = call.status == OpenCodeToolCall.Status.Running || call.status == OpenCodeToolCall.Status.Streaming
            if (call.name == "question" && (call.questions != null || streaming)) {
                if (streaming) PASSES else BREAKS
            } else {
                "tools"
            }
        }
        is OpenCodeTranscriptItem.Notice -> "tools"
        is OpenCodeTranscriptItem.UserText, is OpenCodeTranscriptItem.UserImages, is OpenCodeTranscriptItem.UserFiles ->
            "user:${promptID(item) ?: item.id}"
        is OpenCodeTranscriptItem.SendFailure, is OpenCodeTranscriptItem.RunRetry -> PASSES
        else -> BREAKS
    }
    if (index <= 0 || index >= items.size) return index
    var first = index
    while (first < items.size && kind(items[first]) == PASSES) first++
    if (first >= items.size) return index
    val run = kind(items[first])
    if (run == PASSES || run == BREAKS) return index
    var start = index
    var previous = index - 1
    while (previous >= 0) {
        when (kind(items[previous])) {
            PASSES -> Unit
            run -> start = previous
            else -> return start
        }
        previous--
    }
    return start
}

private const val PASSES = "\u0000passes"
private const val BREAKS = "\u0000breaks"

/** SEAM: real type belongs to :feature:transcript (iOS `Transcript/TranscriptEntryView.swift`); delete when that area merges. */
data class TranscriptSubagent(
    val title: String?,
    val agent: String?,
    val isKnown: Boolean,
    val isRunning: Boolean,
    val needsInput: Boolean,
)

/** SEAM: real type belongs to :feature:transcript (iOS `Transcript/NewMessagesMark.swift`); delete when that area merges. */
data class NewMessagesMark(
    /** The first transcript item the reader hadn't seen. */
    val itemID: String,
    /** The last item it covered. */
    val lastItemID: String? = null,
    /** Changes whenever the divider moves to another item. */
    val generation: Int,
    /** The New Messages Divider experiment was on. */
    val showsDivider: Boolean = true,
)

/** SEAM: real member belongs to :feature:transcript (iOS `Transcript/MarkdownQuote.swift`); delete when that area merges. */
object MarkdownQuote {
    fun blockquote(text: String): String? {
        val normalized = normalizeNewlines(text).trim('\n')
        if (normalized.isBlank()) return null
        return normalized.split("\n").joinToString("\n") { "> $it" }
    }

    fun append(blockquote: String, draft: String): String {
        val normalizedDraft = normalizeNewlines(draft)
        if (normalizedDraft.isBlank()) return "$blockquote\n\n"
        return "${normalizedDraft.trimEnd('\n')}\n\n$blockquote\n\n"
    }

    private fun normalizeNewlines(text: String) = text.replace("\r\n", "\n").replace("\r", "\n")
}

/**
 * SEAM: real member belongs to :feature:transcript's port of iOS `Transcript/NewMessagesMark.swift`
 * (`SessionFeature.State.defaultLeaveDate()`); it extends `SessionFeature.State`, so it lands here.
 */
fun SessionFeature.State.Companion.defaultLeaveDate(): Instant? {
    if (!dependency(ExperimentalFeatures)(ExperimentalFeature.sinceYouLeft)) return null
    val now = dependency(DateClient).now()
    return if (now == Instant.EPOCH) null else now
}

/**
 * SEAM: real member belongs to the port of iOS `Transcript/RunErrorState.swift` (`hidingUndone`),
 * which extends `SessionFeature.State`. What the transcript shows: everything before a staged
 * undo, then one quiet row offering Redo.
 */
fun SessionFeature.State.hidingUndone(items: List<OpenCodeTranscriptItem>): List<OpenCodeTranscriptItem> {
    val boundary = session?.revertMessageID ?: return items
    val index = items.indexOfFirst { item ->
        when (item) {
            is OpenCodeTranscriptItem.UserText -> item.id == boundary
            is OpenCodeTranscriptItem.UserImages -> item.id == "$boundary-images"
            is OpenCodeTranscriptItem.UserFiles -> item.id == "$boundary-files"
            else -> false
        }
    }
    if (index < 0) return items
    val hidden = items.subList(index, items.size).count { it is OpenCodeTranscriptItem.UserText }
    return items.subList(0, index) + OpenCodeTranscriptItem.Undone("undone-$boundary", maxOf(1, hidden))
}

// endregion

// region Composer: files, location, autocomplete, shares

/** SEAM: real type belongs to :feature:system (iOS `System/Share/ShareHandoff.swift`); delete when that area merges. */
data class ComposerStagedFile(val id: UUID, val name: String, val mime: String, val size: Long, val url: String)

/** SEAM: real type belongs to :feature:composer (iOS `Composer/ComposerFileAttachment.swift`); delete when that area merges. */
data class ComposerFileAttachment(
    val id: UUID,
    val name: String,
    val mime: String,
    val size: Long,
    val localURL: String,
    val status: Status,
) {
    sealed interface Status {
        data class Uploading(val sent: Long) : Status
        data class Uploaded(val path: String) : Status
        data class Failed(val message: String) : Status
    }

    val reference: OpenCodeFileReference?
        get() = (status as? Status.Uploaded)?.let { OpenCodeFileReference(name = name, mime = mime, path = it.path) }

    val isUploading: Boolean get() = status is Status.Uploading
    val isFailed: Boolean get() = status is Status.Failed
}

/** SEAM: real type belongs to :feature:composer (iOS `Composer/SharedLocation.swift`); delete when that area merges. */
data class SharedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double? = null,
    val place: String? = null,
    val address: String? = null,
    val capturedAt: Instant,
)

/** SEAM: real type belongs to :feature:composer (iOS `Composer/SharedLocation.swift`); delete when that area merges. */
sealed class LocationClientError(message: String) : Exception(message) {
    data object Denied : LocationClientError("Location access is off for OpenCode. Turn it on in Settings.")
    data object Unavailable : LocationClientError("Couldn’t find your location.")
}

/** SEAM: real type belongs to :feature:composer (iOS `Composer/AutocompleteModels.swift`); delete when that area merges. */
data class ComposerAutocompleteChip(val id: UUID, val token: String)

/** SEAM: real type belongs to :feature:composer (iOS `Composer/AutocompleteFeature.swift`); delete when that area merges. */
data class ComposerAutocompleteState(
    val chips: List<ComposerAutocompleteChip> = emptyList(),
) {
    /** iOS `closePopover()`: the popover closes (no popover state in this stand-in). */
    fun closePopover(): ComposerAutocompleteState = this
}

/** SEAM: real type belongs to :feature:composer (iOS `Composer/AutocompleteFeature.swift`); delete when that area merges. */
sealed interface ComposerAutocompleteAction {
    data object Dismissed : ComposerAutocompleteAction
}

/**
 * SEAM: real type belongs to :feature:composer (iOS `ComposerAutocompleteReducer`); delete when that
 * area merges. The real one takes the client, clock, haptics, experiments and cancel IDs.
 */
object ComposerAutocompleteReducer {
    fun draftChanged(state: SessionFeature.State): Reduced<SessionFeature.State, SessionFeature.Action> = Reduced(state)

    @Suppress("UNUSED_PARAMETER")
    fun reduce(action: ComposerAutocompleteAction, state: SessionFeature.State): Reduced<SessionFeature.State, SessionFeature.Action> =
        Reduced(state)
}

/** SEAM: real type belongs to :feature:system (iOS `System/Share/ShareInbox.swift`); delete when that area merges. */
data class SharedComposerDraft(
    val text: String = "",
    val attachments: List<OpenCodeOutgoingAttachment> = emptyList(),
    val files: List<ComposerStagedFile> = emptyList(),
    val parkedFiles: List<ComposerFileAttachment> = emptyList(),
    val location: SharedLocation? = null,
    val chips: List<ComposerAutocompleteChip> = emptyList(),
    val projectID: String? = null,
    val focusesComposer: Boolean = false,
    val send: Send? = null,
) {
    data class Send(val messageID: String? = null, val sessionID: String? = null)

    companion object {
        fun joined(first: String, second: String): String {
            if (first.isEmpty()) return second
            if (second.isEmpty()) return first
            return "$first\n\n$second"
        }
    }
}

/** SEAM: real type belongs to :feature:system (iOS `System/Share/ShareInbox.swift`); delete when that area merges. */
sealed interface SharedDraftTarget {
    data class Session(val id: String) : SharedDraftTarget
    data object NewSession : SharedDraftTarget

    val sessionID: String? get() = (this as? Session)?.id
}

/**
 * SEAM: real type belongs to iOS `Composer/ComposerFileAttachment.swift` (`ComposerAttachmentsFeature`).
 * Its state carries `SessionFeature.State.UndoSnapshot`, so the port lands in this module.
 */
class ComposerAttachmentsFeature : Reducer<ComposerAttachmentsFeature.State, ComposerAttachmentsFeature.Action> {
    data class State(
        val pendingAttachments: List<OpenCodeOutgoingAttachment> = emptyList(),
        val pendingFiles: List<ComposerFileAttachment> = emptyList(),
        val pendingLocation: SharedLocation? = null,
        val isLocating: Boolean = false,
        val locationError: String? = null,
        val sendAfterUploads: OpenCodeDelivery? = null,
        val unstartedUploadIDs: List<UUID> = emptyList(),
        val undoSnapshot: SessionFeature.State.UndoSnapshot? = null,
    ) {
        val isUploadingFiles: Boolean get() = pendingFiles.any { it.isUploading }
        val isWaitingForUploads: Boolean get() = sendAfterUploads != null
        val hasContent: Boolean get() = pendingAttachments.isNotEmpty() || pendingFiles.isNotEmpty() || pendingLocation != null
    }

    sealed interface Action {
        data class AttachmentAdded(val attachment: OpenCodeOutgoingAttachment) : Action
        data class AttachmentRemoved(val index: Int) : Action
        data class FilesStaged(val files: List<ComposerStagedFile>) : Action
        data class FileUploadProgress(val id: UUID, val sent: Long) : Action
        data class FileUploadFinished(val id: UUID, val result: Result<String>) : Action
        data class FileRetryTapped(val id: UUID) : Action
        data class FileRemoved(val id: UUID) : Action
        data object LocationTapped : Action
        data class LocationResponse(val result: Result<SharedLocation>) : Action
        data object LocationRemoved : Action
        data object LocationErrorDismissed : Action
        data object LocationSettingsTapped : Action
        data object QueuedUploadsStarted : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = Reduced(state)

    /** iOS's static helpers (`readInlineFile`, `cancelUpload`) are in `ModelsSeams.kt`. */
    companion object
}

/**
 * SEAM: real type belongs to iOS `Composer/DictationClient.swift` (`DictationFeature`). Its state
 * carries `SessionFeature.State.DraftState`, so the port lands in this module. State and actions
 * are complete; the reducer is a stand-in.
 */
class DictationFeature : Reducer<DictationFeature.State, DictationFeature.Action> {
    data class State(
        val phase: Phase = Phase.Idle,
        val error: String? = null,
        /** What was typed before the recording, spaced for its words to follow. */
        val base: String = "",
        /** Which recording the dictation events belong to. */
        val recording: Int = 0,
        /** The dictation client's token for the live recording's press. */
        val press: Int = 0,
        /** A recording let go to send whose last words are still finalizing while a newer press records. */
        val sending: Sending? = null,
        /** When the last recording ended, for Raise to Talk's grace after it. */
        val endedAt: Instant? = null,
        /** Recording a message by voice, Telegram style. */
        val hold: Hold? = null,
        /** `stopped` scheduled `deferredSendReady`. */
        val sendPending: Boolean = false,
        /** An answer's recording carried from the last screen, to be discarded when this screen's task starts. */
        val discarded: Discarded? = null,
    ) {
        sealed interface Phase {
            data object Idle : Phase
            data object Preparing : Phase
            data object Recording : Phase
            data class Finishing(val send: OpenCodeDelivery?) : Phase
        }

        data class Sending(
            val recording: Int,
            val draft: SessionFeature.State.DraftState,
            val delivery: OpenCodeDelivery,
            val base: String = "",
            val sessionID: String? = null,
            val keepsAsDraft: Boolean = false,
        )

        data class Hold(val text: String, val isLocked: Boolean = false, val armed: Arm? = null) {
            enum class Arm { lock, cancel }
        }

        data class Discarded(val recording: Int, val press: Int)

        data class Carried(
            val phase: Phase,
            val recording: Int,
            val press: Int,
            val hold: Hold?,
            val transcript: String,
            val sending: Sending?,
            val isAnswer: Boolean = false,
        )

        val isDictating: Boolean get() = phase == Phase.Preparing || phase == Phase.Recording
        val isPreparing: Boolean get() = phase == Phase.Preparing
        val isFinishing: Boolean get() = phase is Phase.Finishing
        val hasLive: Boolean get() = isDictating || isFinishing

        fun blocksRaise(now: () -> Instant): Boolean {
            if (hasLive || sending != null) return true
            val ended = endedAt ?: return false
            return java.time.Duration.between(ended, now()).toMillis() / 1000.0 < RAISE_TO_TALK_GRACE
        }

        /** The words heard so far, without what was typed before them. */
        fun transcript(draft: String): String {
            val text = hold?.text ?: draft
            return if (text.startsWith(base)) text.drop(base.length) else ""
        }

        /** Returns the next state and, when not held, the updated draft text. */
        fun show(transcript: String): Pair<State, String?> {
            val text = base + transcript
            if (hold != null) return copy(hold = hold.copy(text = text)) to null
            return this to text
        }

        fun rebase(typed: String, draft: String): Pair<State, String?> {
            val current = transcript(draft)
            val rebased = copy(base = base(typed))
            if (hold != null) return rebased.show(current).first to typed
            return rebased.show(current)
        }

        companion object {
            /** Seconds after a recording ends that Raise to Talk stays quiet. */
            const val RAISE_TO_TALK_GRACE: Double = 3.0

            fun base(typed: String): String =
                if (typed.isEmpty() || typed.endsWith(" ") || typed.endsWith("\n")) typed else "$typed "
        }
    }

    sealed interface Action {
        data object MicTapped : Action
        data object MicPressBegan : Action
        data class MicPressArmed(val arm: State.Hold.Arm?) : Action
        data object MicPressReleased : Action
        data object MicPressLocked : Action
        data object MicPressInterrupted : Action
        data object MicPressCancelled : Action
        data object InsertTapped : Action
        data object SendTapped : Action
        data object QueueTapped : Action
        data object StopRequested : Action
        data object Ready : Action
        data class Updated(val transcript: String, val recording: Int = 0) : Action
        data class Stopped(val errorMessage: String?, val recording: Int = 0) : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = Reduced(state)

    /** iOS's static helpers (`start`, `stop`, `finish`, `ends`) are in `ModelsSeams.kt`. */
    companion object
}

// endregion

// region Request dock and answers (Composer/RequestAnswer.swift)

// `RequestDockFeature`, `RequestBar`, `AnswerContext` and the answering members of
// `SessionFeature.State` are ported in `SessionFeatureRequestAnswer.kt`.

// endregion

// region Composer persistence (Composer/ComposerPersistence.swift)

/** SEAM: iOS `SessionFeature.PersistedComposerWatch` (`Composer/ComposerPersistence.swift`). */
data class PersistedComposerWatch(
    val serverID: String,
    val sessionID: String?,
    val readsOnView: Boolean,
    val project: OpenCodeProject?,
    val text: String,
    val attachments: List<OpenCodeOutgoingAttachment>,
    val files: List<FileItem>,
    val location: SharedLocation?,
    val chips: List<ComposerAutocompleteChip>,
    val undoSnapshot: SessionFeature.State.UndoSnapshot?,
) {
    sealed interface FilePhase {
        data object Uploading : FilePhase
        data class Uploaded(val path: String) : FilePhase
        data class Failed(val message: String) : FilePhase
    }

    data class FileItem(val id: UUID, val phase: FilePhase) {
        constructor(file: ComposerFileAttachment) : this(
            file.id,
            when (val status = file.status) {
                is ComposerFileAttachment.Status.Uploading -> FilePhase.Uploading
                is ComposerFileAttachment.Status.Uploaded -> FilePhase.Uploaded(status.path)
                is ComposerFileAttachment.Status.Failed -> FilePhase.Failed(status.message)
            },
        )
    }
}

/** iOS `persistedComposerWatch`. */
val SessionFeature.State.persistedComposerWatch: PersistedComposerWatch
    get() {
        val composer = sessionComposer
        return PersistedComposerWatch(
            serverID = server.persistenceID,
            sessionID = session?.id,
            readsOnView = readsOnView,
            project = if (session == null) draftProject else null,
            text = composer.text,
            attachments = composer.attachments,
            files = composer.files.map(PersistedComposerWatch::FileItem),
            location = composer.location,
            chips = composer.chips,
            undoSnapshot = composer.undoSnapshot,
        )
    }

/**
 * SEAM: real member belongs to the port of iOS `Composer/ComposerPersistence.swift`
 * (`reconcilePersistedComposer(from:in:)`): saves the composer as it changes. Stand-in does nothing.
 */
@Suppress("UNUSED_PARAMETER")
fun SessionFeature.reconcilePersistedComposer(
    old: PersistedComposerWatch,
    state: SessionFeature.State,
): Reduced<SessionFeature.State, SessionFeature.Action> = Reduced(state)

// endregion
