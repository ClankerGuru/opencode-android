package ai.opencode.feature.composer

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * A location shared from the composer's + menu: one fix and its nearest address. It
 * travels as a tagged block at the top of the prompt's text, readable by the agent as-is,
 * and the transcript draws it as a card (iOS `SharedLocation`, `Composer/SharedLocation.swift`).
 */
data class SharedLocation(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy in meters, when known. */
    val accuracy: Double? = null,
    /** The nearest place's name, when it isn't just the street address. */
    val place: String? = null,
    /** The nearest address, on one line. */
    val address: String? = null,
    val capturedAt: Instant,
) {
    val coordinateText: String
        get() = String.format(Locale.US, "%.6f, %.6f", latitude, longitude)

    val mapsURL: String
        get() {
            val ll = String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
            val q = place ?: address ?: "Shared Location"
            return "https://maps.apple.com/?ll=${queryEncode(ll)}&q=${queryEncode(q)}"
        }

    /** The block the agent reads. */
    val messageText: String
        get() {
            val lines = mutableListOf(OPEN_TAG)
            place?.let { lines += "Place: $it" }
            address?.let { lines += "Address: $it" }
            lines += "Coordinates: $coordinateText"
            accuracy?.let { lines += "Accuracy: ±${Math.round(it)} m" }
            lines += "Shared: ${ISO.format(capturedAt.truncatedTo(ChronoUnit.SECONDS))}"
            lines += "Map: $mapsURL"
            lines += CLOSE_TAG
            return lines.joinToString("\n")
        }

    /** The prompt's text: the location block, then whatever the user typed. */
    fun prompt(text: String): String = if (text.isEmpty()) messageText else messageText + "\n\n" + text

    /** A parsed prompt: the location, and the user's own text after it. */
    data class Parsed(val location: SharedLocation, val remainder: String)

    companion object {
        const val OPEN_TAG = "<shared-location>"
        const val CLOSE_TAG = "</shared-location>"

        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

        /** Swift's `Date.distantPast`. */
        private val DISTANT_PAST: Instant = Instant.parse("0001-01-01T00:00:00Z")

        /** A prompt that starts with a location block: the location, and the user's own text after it. */
        fun parse(text: String): Parsed? {
            if (!text.startsWith(OPEN_TAG)) return null
            val close = text.indexOf(CLOSE_TAG)
            if (close < 0 || close < OPEN_TAG.length) return null
            val body = text.substring(OPEN_TAG.length, close)
            val fields = mutableMapOf<String, String>()
            for (line in body.split("\n")) {
                if (line.isEmpty()) continue
                val colon = line.indexOf(':')
                if (colon < 0) continue
                fields[line.substring(0, colon).trim(' ', '\t')] = line.substring(colon + 1).trim(' ', '\t')
            }
            val parts = (fields["Coordinates"] ?: "").split(",").filter { it.isNotEmpty() }.map { it.trim(' ', '\t') }
            if (parts.size != 2) return null
            val latitude = parts[0].toDoubleOrNull() ?: return null
            val longitude = parts[1].toDoubleOrNull() ?: return null
            val accuracy = fields["Accuracy"]?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
            val capturedAt = fields["Shared"]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: DISTANT_PAST
            val location = SharedLocation(
                latitude = latitude, longitude = longitude, accuracy = accuracy,
                place = fields["Place"], address = fields["Address"], capturedAt = capturedAt,
            )
            val remainder = text.substring(close + CLOSE_TAG.length).trim()
            return Parsed(location, remainder)
        }

        /** Percent-encodes a query value the way `URLComponents.queryItems` does (`urlQueryAllowed` less `&`, `=`, `+`, `#`). */
        internal fun queryEncode(value: String): String {
            val allowed = "-._~!$'()*,;:@/?"
            val out = StringBuilder()
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = (byte.toInt() and 0xFF)
                val ch = c.toChar()
                if (c < 0x80 && (ch.isLetterOrDigit() || ch in allowed)) out.append(ch)
                else out.append('%').append(String.format(Locale.US, "%02X", c))
            }
            return out.toString()
        }
    }
}
