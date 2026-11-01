package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceWaitsForYou: WhatsNewEntry
    get() = smaller(
        id = "voice-waits-for-you",
        day = "2026-10-30",
        title = "Calls Wait for You to Finish",
        symbol = "waveform",
        summary = "The voice agent no longer talks over you: updates wait until you’ve finished speaking, and talking over it stops it.",
    )
