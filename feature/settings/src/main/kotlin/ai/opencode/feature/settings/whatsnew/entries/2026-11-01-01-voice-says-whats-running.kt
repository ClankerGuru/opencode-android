package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceSaysWhatsRunning: WhatsNewEntry
    get() = smaller(
        id = "voice-says-whats-running",
        day = "2026-11-01",
        title = "Calls Say What’s Still Running",
        symbol = "waveform",
        summary = "When your request takes a while, the voice agent tells you what it’s working on instead of going quiet.",
    )
