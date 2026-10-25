package ai.opencode.feature.session.seams

import ai.opencode.feature.session.SessionFeature
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeAutocompleteMentions
import ai.opencode.server.model.OpenCodeFormField
import ai.opencode.server.model.OpenCodeFormFieldType
import ai.opencode.server.model.OpenCodeFormOption
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodePageContext
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// Stand-ins the SEND group's reducers (send, delivery, inbox, requests) need from areas not
// ported yet. Each says where its real type belongs.

// region Request dock answers (Composer/RequestDockView.swift)

/**
 * SEAM: real type belongs to :feature:composer (iOS `Composer/RequestDockView.swift`
 * `RequestDockAnswer`); delete when that area merges. The answer reading the bar shares with the
 * dock: these members are ported exactly; the dock's captions live with its view.
 */
object RequestDockAnswer {
    fun options(field: OpenCodeFormField): List<OpenCodeFormOption> {
        if (field.type == OpenCodeFormFieldType.Boolean) {
            return listOf(OpenCodeFormOption(value = "true", label = "Yes"), OpenCodeFormOption(value = "false", label = "No"))
        }
        return field.options ?: emptyList()
    }

    fun values(value: OpenCodeFormValue?): List<String> = (value as? OpenCodeFormValue.Strings)?.values ?: emptyList()

    fun knownValues(value: OpenCodeFormValue?, field: OpenCodeFormField): List<String> {
        val known = options(field).map { it.value }.toSet()
        return values(value).filter { it in known }
    }

    /** What was written under Other: the answer that matches no option. */
    fun customText(value: OpenCodeFormValue?, options: List<OpenCodeFormOption>): String {
        val known = options.map { it.value }.toSet()
        return when (value) {
            is OpenCodeFormValue.Str -> if (value.value in known) "" else value.value
            is OpenCodeFormValue.Strings -> value.values.filter { it !in known }.joinToString(", ")
            else -> ""
        }
    }

    fun hasAnswer(value: OpenCodeFormValue?): Boolean = when (value) {
        is OpenCodeFormValue.Str -> value.value.isNotBlank()
        is OpenCodeFormValue.Strings -> value.values.any { it.isNotBlank() }
        is OpenCodeFormValue.Number, is OpenCodeFormValue.Boolean -> true
        null -> false
    }
}

/**
 * SEAM: real member belongs to :feature:composer (iOS `Composer/RequestDockView.swift`
 * `OpenCodeFormRequest.fitsDock`). Questions dock, as does a form with one simple field; anything
 * else opens the request sheet from the dock.
 */
val OpenCodeFormRequest.fitsDock: Boolean
    get() = isQuestion || (fields.size == 1 && fields[0].type != OpenCodeFormFieldType.External)

// endregion

// region Page reader (Browser/BrowserWebViews.swift)

/** SEAM: real type belongs to :feature:media (iOS `Browser/BrowserWebViews.swift` `PageReading`); delete when that area merges. */
data class PageReading(val context: OpenCodePageContext, val screenshot: ByteArray? = null) {
    override fun equals(other: Any?): Boolean =
        other is PageReading && other.context == context && other.screenshot.contentEqualsNullable(screenshot)

    override fun hashCode(): Int = context.hashCode()

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
        if (this == null) other == null else other != null && contentEquals(other)
}

/**
 * SEAM: real member belongs to :feature:media (iOS `PageReaderClient.read(_:)`). Stand-in: the
 * page's address and title only, without its selection, visible text, or screenshot.
 */
@Suppress("RedundantSuspendModifier", "UnusedReceiverParameter")
suspend fun PageReaderClient.read(request: PageShareRequest): PageReading =
    PageReading(OpenCodePageContext(url = request.url, title = request.title))

// endregion

// region Composer autocomplete (Composer/AutocompleteModels.swift, AutocompleteFeature.swift)

/**
 * SEAM: real type belongs to :feature:composer (iOS `Composer/AutocompleteModels.swift`
 * `ComposerAutocompleteDraftResolution`); delete when that area merges. How chosen chips in the
 * draft map onto server requests at send time. Stand-in: without the chip ranges the real one
 * resolves, every draft is a plain prompt with no mentions.
 */
sealed interface ComposerAutocompleteDraftResolution {
    data class CommandExecution(val name: String, val argumentsText: String, val mentions: OpenCodeAutocompleteMentions)

    data class PromptAugmentation(
        val mentions: OpenCodeAutocompleteMentions,
        /** The sent text with each session chip as `@session:<id>`; null when no session chip changed it. */
        val text: String? = null,
    )

    data class Command(val execution: CommandExecution) : ComposerAutocompleteDraftResolution
    data class Prompt(val augmentation: PromptAugmentation) : ComposerAutocompleteDraftResolution

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun resolve(draft: String, sentPromptText: String? = null, chips: List<ComposerAutocompleteChip>): ComposerAutocompleteDraftResolution =
            Prompt(PromptAugmentation(OpenCodeAutocompleteMentions()))

        /** A sent prompt's text as the composer shows it: session chips' `@session:<id>` back to their titles. */
        @Suppress("UNUSED_PARAMETER")
        fun draftText(fromSent: String, chips: List<ComposerAutocompleteChip>): String = fromSent
    }
}

/** SEAM: real type belongs to :feature:composer (iOS `Composer/AutocompleteFeature.swift` `ComposerCommandFailure`). */
class ComposerCommandFailure(val error: OpenCodeClientError, val draft: SessionFeature.State.DraftState) : Exception(error.message)

/**
 * SEAM: iOS `ComposerAutocompleteAction.commandFinished(Result<String, ComposerCommandFailure>)`:
 * a slash command's run ended (the session ID, or the failure with the draft to restore). Delete
 * when :feature:composer's autocomplete merges.
 */
data class ComposerAutocompleteCommandFinished(val result: Result<String>) : ComposerAutocompleteAction

/** SEAM: iOS `ComposerAutocompleteState.clearForSend()`: the popover closes and the chips go with the message. */
fun ComposerAutocompleteState.clearForSend(): ComposerAutocompleteState = copy(chips = emptyList()).closePopover()

/** SEAM: iOS `chip.item.payload` when it's `.session(session)`. Stand-in chips carry no payload. */
@Suppress("UnusedReceiverParameter")
val ComposerAutocompleteChip.sessionPayload: OpenCodeSession? get() = null

// endregion

// region Shared location text (Composer/SharedLocation.swift)

/**
 * SEAM: real members belong to :feature:composer (iOS `Composer/SharedLocation.swift`
 * `messageText`, `prompt(with:)`, `parse(_:)`); ported exactly. Delete when that area merges.
 */
object SharedLocationText {
    const val openTag = "<shared-location>"
    const val closeTag = "</shared-location>"

    fun coordinateText(location: SharedLocation): String =
        String.format(Locale.US, "%.6f, %.6f", location.latitude, location.longitude)

    fun mapsURL(location: SharedLocation): String {
        val ll = String.format(Locale.US, "%.6f,%.6f", location.latitude, location.longitude)
        val q = location.place ?: location.address ?: "Shared Location"
        return "https://maps.apple.com/?ll=${encode(ll)}&q=${encode(q)}"
    }

    /** The block the agent reads. */
    fun messageText(location: SharedLocation): String {
        val lines = mutableListOf(openTag)
        location.place?.let { lines += "Place: $it" }
        location.address?.let { lines += "Address: $it" }
        lines += "Coordinates: ${coordinateText(location)}"
        location.accuracy?.let { lines += "Accuracy: ±${Math.round(it)} m" }
        lines += "Shared: ${DateTimeFormatter.ISO_INSTANT.format(location.capturedAt.truncatedTo(ChronoUnit.SECONDS))}"
        lines += "Map: ${mapsURL(location)}"
        lines += closeTag
        return lines.joinToString("\n")
    }

    /** A prompt that starts with a location block: the location, and the user's own text after it. */
    fun parse(text: String): Pair<SharedLocation, String>? {
        if (!text.startsWith(openTag)) return null
        val close = text.indexOf(closeTag)
        if (close < 0) return null
        val body = text.substring(openTag.length, close)
        val fields = HashMap<String, String>()
        for (line in body.split("\n")) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon < 0) continue
            fields[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
        val parts = (fields["Coordinates"] ?: "").split(",").map { it.trim() }
        if (parts.size != 2) return null
        val latitude = parts[0].toDoubleOrNull() ?: return null
        val longitude = parts[1].toDoubleOrNull() ?: return null
        val accuracy = fields["Accuracy"]?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
        val capturedAt = fields["Shared"]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.MIN
        val location = SharedLocation(
            latitude = latitude, longitude = longitude, accuracy = accuracy,
            place = fields["Place"], address = fields["Address"], capturedAt = capturedAt,
        )
        val remainder = text.substring(close + closeTag.length).trim()
        return location to remainder
    }

    private fun encode(text: String): String = java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}

/** The prompt's text: the location block, then whatever the user typed. */
fun SharedLocation.prompt(text: String): String {
    val block = SharedLocationText.messageText(this)
    return if (text.isEmpty()) block else block + "\n\n" + text
}

// endregion

// region Voice (Voice/VoiceTranscript.swift)

/**
 * SEAM: real type belongs to :feature:voice (iOS `Voice/VoiceTranscript.swift` `VoiceHandoff`);
 * delete when that area merges. Stand-in: recognizes a delegation by its header only.
 */
object VoiceHandoff {
    private const val header = "Voice delegation."
    private const val continuedHeader = "Voice delegation (continued)."

    /** Non-null when [text] is a call's delegation rather than the reader's own words. */
    fun parse(text: String): String? =
        if (text.startsWith(header) || text.startsWith(continuedHeader)) text else null
}

// endregion

// region Scene window (System)

/**
 * SEAM: real member belongs to :feature:system (iOS `SceneWindowClient.isFront`): whether this
 * screen's window is the frontmost one. Stand-in: one window, always in front. Delete when that area merges.
 */
@Suppress("UnusedReceiverParameter")
val SceneWindowClient.isFront: Boolean get() = true

// endregion
