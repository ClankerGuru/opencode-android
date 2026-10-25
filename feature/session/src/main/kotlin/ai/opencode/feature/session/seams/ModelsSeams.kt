package ai.opencode.feature.session.seams

import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.currentModelInfo
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.model.ModelVariant
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.text.Normalizer
import java.time.Instant
import java.util.UUID

// Stand-ins the MODELS group (iOS `SessionFeature+Models/Agents/Dictation/Attachments/Browser`)
// needs from areas not merged yet. Each carries only what those reducers use.

// region Dictation (Composer/DictationClient.swift, Composer/DictationAudio.swift)

/** SEAM: real type belongs to :feature:composer (iOS `Composer/DictationAudio.swift` `DictationEnd`); delete when that area merges. */
enum class DictationEnd {
    /** Stop: the words land in the draft, and the session is held ready a while. */
    insert,
    /** Let go to send (or Send in the bar). */
    send,
    /** Interrupted or the recognizer failed: never held. */
    failed,
}

/** SEAM: real type belongs to :feature:composer (iOS `Composer/DictationAudio.swift` `DictationHoldRelease`); delete when that area merges. */
enum class DictationHoldRelease {
    /** The composer sent a message. Ends only a hold kept after an insert. */
    sent,
    /** The person left the session. Ends only a hold kept after an insert. */
    left,
    /** A call is starting. */
    call,
    expired,
    background,
    /** Another app started playing. */
    silenced,
    /** Someone else claimed the shared session. */
    claimed,
    interrupted;

    /** Whether it ends a hold kept after [end]. */
    fun ends(end: DictationEnd): Boolean = when (this) {
        sent, left -> end == DictationEnd.insert
        else -> true
    }
}

/** SEAM: iOS `DictationFeature.CancelID.recording(_:)`; delete when :feature:composer's `DictationFeature` merges. */
data class DictationRecordingCancelID(val recording: Int)

/**
 * SEAM: iOS `DictationFeature.start(_:draft:dictation:)` (`Composer/DictationClient.swift`); delete when
 * that area merges. Starts the mic from the touch and streams its words into the draft.
 */
fun DictationFeature.Companion.start(
    state: DictationFeature.State,
    draft: String,
    dictation: DictationClient,
): Reduced<DictationFeature.State, DictationFeature.Action> {
    val recording = state.recording
    // The mic starts now, from the touch: the effect below only adopts the press's recording.
    val press = dictation.begin()
    val next = state.copy(
        error = null,
        phase = DictationFeature.State.Phase.Preparing,
        // The engine streams whole-session transcripts; they land after whatever was already typed.
        base = DictationFeature.State.base(draft),
        press = press,
    )
    val effect = Effect.run<DictationFeature.Action>(
        onError = { error, send ->
            send(DictationFeature.Action.Stopped(error.message ?: "Dictation failed", recording))
        },
    ) { send ->
        val transcripts = try {
            dictation.start(press)
        } catch (cancelled: CancellationException) {
            // Stopped before it reached the mic, or it yielded the session to a call: over, with no
            // words. (A cancelled effect sends nothing.)
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw cancelled
            send(DictationFeature.Action.Stopped(null, recording))
            return@run
        }
        send(DictationFeature.Action.Ready)
        transcripts.collect { send(DictationFeature.Action.Updated(it, recording)) }
        send(DictationFeature.Action.Stopped(null, recording))
    }.cancellable(DictationRecordingCancelID(recording), cancelInFlight = true)
    return Reduced(next, effect)
}

/**
 * SEAM: iOS `DictationFeature.stop(_:endedAt:dictation:)`; delete when that area merges. Discards
 * dictation: tears down the mic at once and cancels the transcript stream. A no-op unless active.
 */
fun DictationFeature.Companion.stop(
    state: DictationFeature.State,
    endedAt: Instant?,
    dictation: DictationClient,
): Reduced<DictationFeature.State, DictationFeature.Action> {
    val phase = state.phase
    var next = state.copy(phase = DictationFeature.State.Phase.Idle, hold = null)
    if (phase == DictationFeature.State.Phase.Idle) return Reduced(next)
    if (endedAt != null) next = next.copy(endedAt = endedAt)
    val press = next.press
    return Reduced(
        next,
        Effect.merge(
            Effect.cancel(DictationRecordingCancelID(next.recording)),
            Effect.run { dictation.cancel(press) },
        ),
    )
}

/**
 * SEAM: iOS `DictationFeature.finish(_:send:dictation:)`; delete when that area merges. Stops capture
 * but keeps the transcript stream alive so the engine's finalized text still lands.
 */
fun DictationFeature.Companion.finish(
    state: DictationFeature.State,
    send: OpenCodeDelivery?,
    dictation: DictationClient,
): Reduced<DictationFeature.State, DictationFeature.Action> {
    if (!state.isDictating) return Reduced(state)
    val press = state.press
    val end = if (send == null) DictationEnd.insert else DictationEnd.send
    return Reduced(
        state.copy(phase = DictationFeature.State.Phase.Finishing(send)),
        Effect.run {
            dictation.ends(press, end)
            dictation.stop(press)
        },
    )
}

/** SEAM: iOS `DictationFeature.State.enterBackground()`; delete when that area merges. */
fun DictationFeature.State.enterBackground(): DictationFeature.State {
    var next = this
    val phase = phase
    if (phase is DictationFeature.State.Phase.Finishing && phase.send != null) {
        next = next.copy(phase = DictationFeature.State.Phase.Finishing(send = null))
    }
    next.sending?.let { next = next.copy(sending = it.copy(keepsAsDraft = true)) }
    return next
}

// endregion

// region Attachments (Composer/ComposerFileAttachment.swift, Composer/AttachmentFileType.swift)

/** SEAM: iOS `ComposerAttachmentsFeature.CancelID.upload(_:)`; delete when that area merges. */
data class ComposerUploadCancelID(val id: UUID)

/** SEAM: iOS `ComposerFileAttachment.init(_:status:)`; delete when :feature:composer merges. */
fun ComposerFileAttachment(
    file: ComposerStagedFile,
    status: ComposerFileAttachment.Status = ComposerFileAttachment.Status.Uploading(0),
): ComposerFileAttachment = ComposerFileAttachment(file.id, file.name, file.mime, file.size, file.url.path, status)

/** SEAM: iOS `ComposerAttachmentsFeature.readInlineFile(_:)`; delete when that area merges. */
fun ComposerAttachmentsFeature.Companion.readInlineFile(file: ComposerStagedFile): Effect<ComposerAttachmentsFeature.Action> =
    Effect.run { send ->
        val data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { file.url.readBytes() }.getOrNull()
        }
        if (data != null) {
            send(ComposerAttachmentsFeature.Action.AttachmentAdded(OpenCodeOutgoingAttachment(id = file.id.toString(), data = data, mime = file.mime, name = file.name)))
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { file.url.delete() } }
    }

/** SEAM: iOS `ComposerAttachmentsFeature.cancelUpload(_:discardURL:)`; delete when that area merges. */
fun ComposerAttachmentsFeature.Companion.cancelUpload(id: UUID, discardURL: String): Effect<ComposerAttachmentsFeature.Action> =
    Effect.merge(
        Effect.cancel(ComposerUploadCancelID(id)),
        Effect.run {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { File(discardURL.removePrefix("file://")).delete() } }
        },
    )

// endregion

// region Model picker (Composer/ModelPicker)

/**
 * SEAM: real type belongs to :feature:composer (iOS `Composer/ModelPicker/ModelLevelScale.swift`);
 * delete when that area merges. The thinking levels a model offers: its default first (null), then
 * its variants lowest to highest.
 */
class ModelLevelScale(variants: List<String>) {
    val levels: List<String?> = listOf<String?>(null) + ModelVariant.sorted(variants.filter { it != "default" })

    override fun equals(other: Any?): Boolean = other is ModelLevelScale && other.levels == levels
    override fun hashCode(): Int = levels.hashCode()

    val count: Int get() = levels.size
    /** A model without variants has only its default: nothing to change. */
    val isAdjustable: Boolean get() = levels.size > 1
    /** The variants alone, for the level bars. */
    val variants: List<String> get() = levels.filterNotNull()

    /** A level's stop; the default, `"default"`, or a level the model no longer offers is the first. */
    fun index(of: String?): Int {
        if (of == null || of == "default") return 0
        return levels.indexOf(of).takeIf { it >= 0 } ?: 0
    }

    fun level(at: Int): String? = levels[at.coerceIn(0, levels.size - 1)]

    /** One step up or down, stopping at the ends (TalkBack's adjustable action). */
    fun stepped(from: String?, by: Int): String? = level(index(from) + by)

    /** Holding the meter and sliding: every [step] from where the hold began is one level, rounded, kept within the scale. */
    fun scrubIndex(start: Int, translation: Float, step: Float = SCRUB_STEP): Int =
        (start + Math.round(translation / step)).coerceIn(0, levels.size - 1)

    companion object {
        const val SCRUB_STEP = 30f

        fun label(level: String?): String = if (level == null || level == "default") "Default" else ModelVariant.label(level)
    }
}

/** SEAM: iOS `currentLevelScale` (`ModelPickerSections.swift`): the current model's levels, when it has any to choose between. */
val SessionFeature.State.currentLevelScale: ModelLevelScale?
    get() {
        val model = currentModelInfo ?: return null
        val scale = ModelLevelScale(model.variants)
        return if (scale.isAdjustable) scale else null
    }

/** SEAM: iOS `currentLevel` (`ModelPickerSections.swift`): the current level; null is the model's default. */
val SessionFeature.State.currentLevel: String?
    get() = currentModel?.variant?.takeIf { it != "default" }

private val levelSynonyms = mapOf(
    "off" to "none", "none" to "none", "minimal" to "minimal", "min" to "minimal", "low" to "low",
    "medium" to "medium", "med" to "medium", "high" to "high", "xhigh" to "xhigh", "max" to "max",
    "maximum" to "max", "ultra" to "ultra", "default" to "default",
)

/** SEAM: iOS `ModelSearch.normalize(_:)`: lowercased words without accents or punctuation, keeping a version's dot. */
fun ModelSearch.normalize(text: String): String {
    val folded = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
    val characters = folded.toCharArray()
    val out = StringBuilder()
    for ((index, character) in characters.withIndex()) {
        if (character.isLetterOrDigit()) {
            out.append(character)
        } else if (character in ".-_" && index > 0 && index + 1 < characters.size &&
            characters[index - 1].isDigit() && characters[index + 1].isDigit()
        ) {
            out.append('.')
        } else if (out.isNotEmpty() && out.last() != ' ') {
            out.append(' ')
        }
    }
    return out.toString().trimEnd(' ')
}

/** SEAM: iOS `ModelSearch.parseLevelQuery(_:)`: `"argon high"` → (`["argon"]`, `"high"`); `"sonnet extra high"` → (`["sonnet"]`, `"xhigh"`). */
fun ModelSearch.parseLevelQuery(query: String): Pair<List<String>, String?> {
    val raw = normalize(query).split(' ').filter { it.isNotEmpty() }
    if (raw.size <= 1) return raw to null
    val terms = mutableListOf<String>()
    var level: String? = null
    var index = 0
    while (index < raw.size) {
        val word = raw[index]
        val next = raw.getOrNull(index + 1)
        if (level == null && word == "extra" && next == "high" && raw.size > 2) {
            level = "xhigh"
            index += 2
            continue
        }
        val mapped = levelSynonyms[word]
        if (level == null && mapped != null && raw.withIndex().any { it.index != index && levelSynonyms[it.value] == null }) {
            level = mapped
            index += 1
            continue
        }
        terms += word
        index += 1
    }
    return terms to level
}

/** SEAM: iOS `ModelSearch.resolveVariant(_:for:)`: a parsed level word against the variants [model] offers; `"default"` is null. */
fun ModelSearch.resolveVariant(requested: String, model: OpenCodeModel): String? {
    if (requested == "default") return null
    val sorted = ModelVariant.sorted(model.variants.filter { it != "default" })
    if (sorted.isEmpty()) return null
    sorted.firstOrNull { it.equals(requested, ignoreCase = true) }?.let { return it }
    return when (requested.lowercase()) {
        "max", "ultra", "xhigh" -> sorted.last()
        "none", "minimal", "low" -> sorted.first()
        "medium" -> sorted[sorted.size / 2]
        else -> sorted.last()
    }
}

/** SEAM: iOS `ModelSearch.resolvePick` result. */
data class ModelSearchPick(val model: OpenCodeModel, val variant: String?, val hasExplicitLevel: Boolean)

/**
 * SEAM: iOS `ModelSearch.resolvePick(_:query:favorites:recents:)`: the top match for Return in the
 * search field, plus any explicit level word. Stand-in ranking: the seam's [ModelSearch.rank] on the
 * whole query, else on the query without its level word (iOS scores both in one pass).
 */
fun ModelSearch.resolvePick(
    models: List<OpenCodeModel>,
    query: String,
    favorites: Set<String> = emptySet(),
    @Suppress("UNUSED_PARAMETER") recents: List<String> = emptyList(),
): ModelSearchPick? {
    val (terms, requested) = parseLevelQuery(query)
    val ranked = rank(models, query, favorites).ifEmpty {
        if (requested != null && terms.isNotEmpty()) rank(models, terms.joinToString(" "), favorites) else emptyList()
    }
    val top = ranked.firstOrNull() ?: return null
    if (requested != null) return ModelSearchPick(top, resolveVariant(requested, top), hasExplicitLevel = true)
    return ModelSearchPick(top, null, hasExplicitLevel = false)
}

// endregion

// region Browser and links (Browser/BrowserLinkPolicy.swift, Media/LinkPreviews/LinkPreview.swift)

/**
 * SEAM: real type belongs to :feature:media (iOS `Browser/BrowserLinkPolicy.swift`, with
 * `LinkPreviewPolicy.isPublicWebURL`); delete when that area merges. Public `http(s)` pages only:
 * no IP literals, single-label, local or tailnet hosts, credentials or signed queries.
 */
object BrowserLinkPolicy {
    /** Loopback pages the session lab serves count as public in lab runs (off in this port). */
    const val allowsLoopbackForLab = false

    private val privateSuffixes = listOf(
        ".localhost", ".local", ".internal", ".intranet", ".lan", ".home", ".corp", ".home.arpa",
        ".test", ".invalid", ".ts.net", ".onion",
    )

    fun isPublicWebPage(url: String, allowsLoopback: Boolean = allowsLoopbackForLab): Boolean {
        if (OpenCodePairingLink.shouldHandleOpenURL(url)) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != "http") return false
        if (uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase()?.trim('.') ?: return false
        if (host.isEmpty()) return false
        if (allowsLoopback && isLoopback(host)) return true
        if (isIPLiteral(host)) return false
        // A single label (`intranet`, `nas`) is a local name.
        if (!host.contains('.')) return false
        if (privateSuffixes.any { host == it.drop(1) || host.endsWith(it) }) return false
        return queryNames(uri).none(::isCredential)
    }

    fun isLoopback(host: String): Boolean = host.trim('.') in setOf("localhost", "127.0.0.1", "::1", "[::1]")

    private fun isIPLiteral(host: String): Boolean {
        if (host.contains(':') || host.startsWith("[")) return true
        val labels = host.split('.').filter { it.isNotEmpty() }
        return labels.isNotEmpty() && labels.all { label -> label.all { it.isDigit() } }
    }

    private fun queryNames(uri: URI): List<String> =
        uri.rawQuery?.split('&')?.mapNotNull { pair ->
            pair.substringBefore('=').takeIf { it.isNotEmpty() }?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        } ?: emptyList()

    private fun isCredential(raw: String): Boolean {
        val name = raw.lowercase()
        val exact = setOf(
            "key", "apikey", "api_key", "auth", "authorization", "code", "sig", "sid", "session", "sessionid",
            "jwt", "otp", "ticket", "pass", "pwd", "credential", "credentials", "x-amz-credential", "x-goog-credential",
        )
        return name in exact || listOf("token", "signature", "secret", "password").any { name.contains(it) }
    }
}

// endregion

// region Voice (Voice/VoiceFeature.swift)

/** SEAM: iOS `VoiceFeature.State.Phase.isTerminal`; delete when :feature:voice merges. */
val VoiceFeature.State.Phase.isTerminal: Boolean
    get() = this == VoiceFeature.State.Phase.Ended || this is VoiceFeature.State.Phase.Failed || this == VoiceFeature.State.Phase.Dropped

/**
 * SEAM: iOS `VoiceFeature.State.moveDraft(to:)`; delete when :feature:voice merges. The draft the call
 * is on changed project: the same conversation, created somewhere else.
 */
fun VoiceFeature.State.moveDraft(directory: String): VoiceFeature.State {
    if (target.sessionID != null || target.directory == directory) return this
    return copy(draftSessionID = null, target = target.copy(directory = directory))
}

// endregion

// region Composer persistence (Composer/ComposerPersistence.swift)

/**
 * SEAM: iOS `SessionFeature.State.FamilyDrafts` (`Composer/ComposerPersistence.swift`); delete when the
 * composer persistence port lands. iOS keeps a family member's draft in `ComposerPersistence` while the
 * screen reads on view, and in `catchUpFamilyDrafts` for a Catch Up card. Stand-in: in memory
 * (`catchUpFamilyDrafts`) either way, so drafts survive family switches for the screen's life.
 */
data class FamilyDrafts(
    val serverID: String,
    val readsOnView: Boolean,
    val catchUpDrafts: Map<String, SessionFeature.State.DraftState>,
) {
    operator fun get(sessionID: String): SessionFeature.State.DraftState? = catchUpDrafts[sessionID]

    /** iOS `familyDrafts[sessionID] = value`: an empty draft clears the entry. */
    fun set(sessionID: String, value: SessionFeature.State.DraftState?): FamilyDrafts =
        if (value != null && !value.isEmpty) copy(catchUpDrafts = catchUpDrafts + (sessionID to value))
        else copy(catchUpDrafts = catchUpDrafts - sessionID)

    val keys: List<String> get() = catchUpDrafts.keys.toList()
}

/** SEAM: iOS `SessionFeature.State.familyDrafts` (get). */
val SessionFeature.State.familyDrafts: FamilyDrafts
    get() = FamilyDrafts(server.persistenceID, readsOnView, catchUpFamilyDrafts)

/** SEAM: iOS `SessionFeature.State.familyDrafts` (set). */
fun SessionFeature.State.withFamilyDrafts(value: FamilyDrafts): SessionFeature.State = copy(catchUpFamilyDrafts = value.catchUpDrafts)

/** SEAM: iOS `SessionFeature.State.DraftState.isEmpty` (`Composer/ComposerPersistence.swift`). */
val SessionFeature.State.DraftState.isEmpty: Boolean
    get() = text.isEmpty() && attachments.isEmpty() && files.isEmpty() && location == null && undoSnapshot == null

/**
 * SEAM: iOS `restorePersistedComposer(_:)` (`Composer/ComposerPersistence.swift`): binds the outbox,
 * restores the saved draft and the outbox's sends for this screen. Stand-in: nothing is persisted yet.
 */
fun SessionFeature.restorePersistedComposer(state: SessionFeature.State): SessionFeature.State = state

/** SEAM: iOS `syncAndFlushPersistedComposer(_:)`: saves the composer now and flushes. Stand-in: nothing is persisted yet. */
@Suppress("UNUSED_PARAMETER")
fun SessionFeature.syncAndFlushPersistedComposer(state: SessionFeature.State): Effect<SessionFeature.Action> = Effect.none()

// endregion
