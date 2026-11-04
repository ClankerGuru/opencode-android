package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceBackgroundResults: WhatsNewEntry
    get() = smaller(
        id = "voice-background-results",
        day = "2026-10-25",
        title = "Background Results in Calls",
        symbol = "waveform",
        summary = "When a session you asked during a call starts work in the background, the call tells you what it found as soon as it’s done, even after you’ve moved on to other things.",
    )
