package ai.opencode.feature.session.seams

// Stand-ins the SEND group's reducers (send, delivery, inbox, requests) need from :feature:voice
// and :feature:system, which this module doesn't take yet.

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
