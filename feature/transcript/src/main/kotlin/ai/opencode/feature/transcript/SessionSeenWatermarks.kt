package ai.opencode.feature.transcript

import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.feature.transcript.rows.TranscriptTimeStamps
import kotlinx.serialization.Serializable

// iOS declares `SessionSeenWatermark(s)` and the `sessionSeenWatermarks` key in
// `Core/SharedKeys.swift`, but the watermark compares item IDs through `TranscriptTimeStamps`,
// which lives in this module; `:core` can't see it, so the type and its key live here (noted in
// docs/parity/core.md). `isAtOrBefore`/`isBefore` are iOS `TranscriptTimeStamps` members, added as
// extensions so `rows/TranscriptTimeStamps.kt` stays as ported.

/** iOS `TranscriptTimeStamps.isAtOrBefore(_:id:)`: whether [id] carries an OpenCode timestamp minted at or before [milliseconds]. */
fun TranscriptTimeStamps.isAtOrBefore(milliseconds: Long, id: String): Boolean? {
    val ahead = aheadOf(milliseconds, id) ?: return null
    return ahead == 0L || ahead > (TIME_MASK ushr 1)
}

/** iOS `TranscriptTimeStamps.isBefore(_:id:)`: whether [id] carries an OpenCode timestamp minted strictly before [milliseconds]. */
fun TranscriptTimeStamps.isBefore(milliseconds: Long, id: String): Boolean? {
    val ahead = aheadOf(milliseconds, id) ?: return null
    return ahead > (TIME_MASK ushr 1)
}

private const val TIME_MASK = 0xF_FFFF_FFFFL

/** How far [id]'s time is ahead of [milliseconds], modulo the 36 bits the ID keeps; null when either is missing. */
private fun aheadOf(milliseconds: Long, id: String): Long? {
    if (milliseconds <= 0) return null
    val value = parsedIDValue(id) ?: return null
    val itemMS = (value ushr 12) and TIME_MASK
    val cutoffMS = milliseconds and TIME_MASK
    return (itemMS - cutoffMS) and TIME_MASK
}

/** iOS `TranscriptTimeStamps.parsedValue(ofID:)`: the 48-bit prefix of an OpenCode ID's 26-character body. */
private fun parsedIDValue(id: String): Long? {
    val underscore = id.indexOf('_')
    if (underscore < 0) return null
    val bodyStart = underscore + 1
    var bodyEnd = bodyStart
    while (bodyEnd < id.length && id[bodyEnd] != '-') bodyEnd++
    if (bodyEnd - bodyStart != 26) return null
    var value = 0L
    for (i in bodyStart until bodyStart + 12) {
        val digit = when (val c = id[i]) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            else -> return null
        }
        value = (value shl 4) or digit.toLong()
    }
    for (i in bodyStart + 12 until bodyEnd) {
        val c = id[i]
        if (c !in '0'..'9' && c !in 'A'..'Z' && c !in 'a'..'z') return null
    }
    return value
}

/**
 * iOS `SessionSeenWatermark`: how far the reader had seen a session as they last left it: the
 * last seen item, the read time the unseen rows count from, and the server read it had already
 * acknowledged while rows stayed unseen.
 */
@Serializable
data class SessionSeenWatermark(
    val itemID: String? = null,
    val viewedMilliseconds: Long? = null,
    val acknowledgedViewedMilliseconds: Long? = null,
) {
    /** iOS `isOlder(thanServerViewed:)`: a read elsewhere since this watermark was kept makes it stale. */
    fun isOlderThanServerViewed(serverViewed: Long): Boolean {
        if (serverViewed <= 0) return false
        val knownServerViewed = maxOf(viewedMilliseconds ?: 0, acknowledgedViewedMilliseconds ?: 0)
        if (knownServerViewed >= serverViewed) return false
        if (itemID != null) {
            TranscriptTimeStamps.isBefore(serverViewed, itemID)?.let { return it }
        }
        if (knownServerViewed > 0) return serverViewed > knownServerViewed
        return false
    }
}

/** iOS `SessionSeenWatermarks`: watermarks by `serverID|sessionID`, the most recent [maximumEntries] kept. */
@Serializable
data class SessionSeenWatermarks(
    val entries: Map<String, SessionSeenWatermark> = emptyMap(),
    val order: List<String> = emptyList(),
) {
    operator fun get(key: String): SessionSeenWatermark? = entries[key]

    /** iOS `set(_:for:)`: stores [watermark] as the most recent, evicting the oldest past [maximumEntries]. */
    fun setting(watermark: SessionSeenWatermark, key: String): SessionSeenWatermarks {
        val entries = entries.toMutableMap()
        entries[key] = watermark
        val order = (order - key + key).toMutableList()
        while (order.size > maximumEntries) entries.remove(order.removeAt(0))
        if (entries.size > order.size) {
            val retained = order.toHashSet()
            entries.keys.retainAll(retained)
        }
        return SessionSeenWatermarks(entries, order)
    }

    /** iOS `removeValue(forKey:)`. */
    fun removing(key: String): SessionSeenWatermarks =
        SessionSeenWatermarks(entries - key, order - key)

    companion object {
        const val maximumEntries = 64
    }
}

private val sessionSeenWatermarksKey = SharedKey.fileStorage(
    FileDirectory.Documents,
    "session-seen-watermarks.json",
    SessionSeenWatermarks(),
)

/**
 * iOS `SharedKeys.sessionSeenWatermarks`: compact read watermark per session (`serverID|sessionID`
 * → last seen item ID or timestamp), capped to recent sessions with LRU eviction so a cold launch
 * into a session with new messages since it was last viewed still marks what arrived meanwhile.
 */
val SharedKeys.sessionSeenWatermarks: SharedKey<SessionSeenWatermarks> get() = sessionSeenWatermarksKey
