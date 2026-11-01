package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.dictationKeepsGoing: WhatsNewEntry
    get() = feature(
        id = "dictation-keeps-going",
        day = "2026-10-08",
        title = "Dictate and Send",
        symbol = "mic.fill",
        tint = Tint.orange,
        summary = "Tap the mic to dictate, then send straight from the recording bar, or tap Stop to review it first.",
        details = listOf(
            "Hold the mic to talk, slide up to lock, or slide left to cancel.",
            "Recording keeps going when you leave the app, with a Live Activity to stop it.",
            "Switch sessions mid-recording and Send goes to the one on screen.",
        ),
        example = "Tap the mic, say a sentence, and tap Send",
    )
