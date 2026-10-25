package ai.opencode.feature.transcript.user

import ai.opencode.server.model.AttachmentFileType
import android.net.Uri
import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

// Minimal value ports of iOS types this package draws from other areas (hand-offs: `:feature:voice`
// owns `VoiceHandoff`, `:feature:composer` owns `SharedLocation` and `AttachmentFileType`'s labels).
// Kept internal so the owning areas can replace them without touching callers outside `user/`.

/** iOS `Voice/VoiceTranscript.swift` `VoiceHandoff`: only the parse the transcript needs. */
object VoiceHandoff {
    @Immutable
    data class Delegation(
        /** The user's own words. */
        val instruction: String,
        val transcriptDelta: List<String>,
        val context: String? = null,
        /** The voice agent's framing; prompts from before it was sent have none. */
        val framing: String? = null,
        /** True when a snapshot of the voice call log was attached to this message. */
        val callLogAttached: Boolean = false,
    ) {
        /** What the session shows: the voice agent prompting on the user's behalf. */
        val displayText: String get() = framing ?: instruction
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Delegation? {
        // The header's wording may change; its opening and the body's shape won't.
        if (!text.startsWith("Voice delegation.") && !text.startsWith("Voice delegation (continued).")) return null
        val newline = text.indexOf('\n')
        if (newline < 0) return null
        var rawBody = text.substring(newline + 1)
        var hadAttachedCallLogLine = false
        while (true) {
            val lineBreak = rawBody.lastIndexOf('\n')
            if (lineBreak < 0) break
            val lastLine = rawBody.substring(lineBreak + 1).trim()
            if (lastLine.isEmpty()) {
                rawBody = rawBody.substring(0, lineBreak)
                continue
            }
            if (!(lastLine.startsWith("Attached file: `") && lastLine.endsWith("`"))) break
            if (lastLine.contains("voice-call-")) hadAttachedCallLogLine = true
            rawBody = rawBody.substring(0, lineBreak)
        }
        val body = try {
            json.parseToJsonElement(rawBody) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        fun string(key: String): String? = (body[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val rawInstruction = body["instruction"] as? JsonPrimitive ?: return null
        if (!rawInstruction.isString) return null
        val instruction = rawInstruction.content.trim()
        if (instruction.isEmpty()) return null
        val delta = (body["transcript_delta"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val framing = string("voice")?.trim()
        return Delegation(
            instruction = instruction,
            transcriptDelta = delta,
            context = string("context")?.trim(),
            framing = framing?.takeIf { it.isNotEmpty() },
            callLogAttached = (body["call_log_attached"] as? JsonPrimitive)?.booleanOrNull == true || hadAttachedCallLogLine,
        )
    }
}

/**
 * A location shared from the composer's + menu: one fix and its nearest address (iOS
 * `Composer/SharedLocation.swift`, the value and its parse only). It travels as a tagged block
 * at the top of the prompt's text, and the transcript draws it as a card.
 */
@Immutable
data class SharedLocation(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy in meters, when known. */
    val accuracy: Double? = null,
    /** The nearest place's name, when it isn't just the street address. */
    val place: String? = null,
    /** The nearest address, on one line. */
    val address: String? = null,
    val capturedAt: Instant = Instant.MIN,
) {
    val coordinateText: String get() = String.format(Locale.US, "%.6f, %.6f", latitude, longitude)

    val mapsURL: String
        get() = Uri.parse("https://maps.apple.com/").buildUpon()
            .appendQueryParameter("ll", String.format(Locale.US, "%.6f,%.6f", latitude, longitude))
            .appendQueryParameter("q", place ?: address ?: "Shared Location")
            .build().toString()

    val accuracyText: String? get() = accuracy?.let { "±${it.roundToInt()} m" }

    companion object {
        const val openTag = "<shared-location>"
        const val closeTag = "</shared-location>"

        /** A prompt that starts with a location block: the location, and the user's own text after it. */
        fun parse(text: String): Pair<SharedLocation, String>? {
            if (!text.startsWith(openTag)) return null
            val close = text.indexOf(closeTag)
            if (close < openTag.length) return null
            val body = text.substring(openTag.length, close)
            val fields = mutableMapOf<String, String>()
            for (line in body.split('\n')) {
                if (line.isEmpty()) continue
                val colon = line.indexOf(':')
                if (colon < 0) continue
                fields[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
            }
            val parts = (fields["Coordinates"] ?: "").split(',').filter { it.isNotEmpty() }.map { it.trim() }
            if (parts.size != 2) return null
            val latitude = parts[0].toDoubleOrNull() ?: return null
            val longitude = parts[1].toDoubleOrNull() ?: return null
            val accuracy = fields["Accuracy"]?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
            val capturedAt = fields["Shared"]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.MIN
            val location = SharedLocation(latitude, longitude, accuracy, fields["Place"], fields["Address"], capturedAt)
            val remainder = text.substring(close + closeTag.length).trim()
            return location to remainder
        }
    }
}

/** iOS `AttachmentFileType.label` / `symbolName`: a file chip's subtitle and glyph. */
internal object UserFileType {
    private const val PDF_MIME = "application/pdf"

    /** The common subset of the language names desktop takes from Shiki. */
    private val languageNames = mapOf(
        "c" to "C", "cjs" to "JavaScript", "cpp" to "C++", "cs" to "C#", "css" to "CSS", "dart" to "Dart",
        "go" to "Go", "html" to "HTML", "java" to "Java", "js" to "JavaScript",
        "json" to "JSON", "jsx" to "JSX", "kt" to "Kotlin", "lua" to "Lua", "md" to "Markdown", "mjs" to "JavaScript",
        "php" to "PHP", "py" to "Python", "rb" to "Ruby", "rs" to "Rust", "sh" to "Shell", "sql" to "SQL",
        "swift" to "Swift", "toml" to "TOML", "ts" to "TypeScript", "tsx" to "TSX",
        "xml" to "XML", "yaml" to "YAML", "yml" to "YAML", "zsh" to "Zsh",
    )

    private val archiveMimes = setOf(
        "application/zip", "application/x-zip-compressed", "application/gzip", "application/x-gzip", "application/x-tar",
        "application/x-7z-compressed", "application/x-rar-compressed", "application/vnd.rar", "application/x-bzip2",
    )

    /** The chip subtitle: "PDF", a language name, or the extension in capitals. */
    fun label(name: String, mime: String): String {
        if (mime == PDF_MIME) return "PDF"
        val suffix = AttachmentFileType.fileExtension(name)
        if (suffix.isEmpty()) return "File"
        return languageNames[suffix] ?: suffix.uppercase(Locale.ROOT)
    }

    fun symbolName(name: String, mime: String): String {
        if (mime == PDF_MIME) return "doc.richtext"
        if (mime.startsWith("image/")) return "photo"
        if (mime.startsWith("video/")) return "film"
        if (mime.startsWith("audio/")) return "waveform"
        if (mime == "text/plain") {
            return if (languageNames[AttachmentFileType.fileExtension(name)] == null) "doc.text" else "chevron.left.forwardslash.chevron.right"
        }
        // UTType conformance → MIME families.
        if (mime in archiveMimes) return "doc.zipper"
        if (mime.contains("spreadsheet") || mime == "text/csv" || mime == "application/vnd.ms-excel") return "tablecells"
        if (mime.contains("presentation") || mime == "application/vnd.ms-powerpoint") return "rectangle.on.rectangle"
        return "doc"
    }
}
