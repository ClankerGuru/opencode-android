package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceMessages: WhatsNewEntry
    get() = smaller(
        id = "voice-messages",
        day = "2026-10-06",
        title = "Voice Messages",
        symbol = "waveform",
        summary = "What Voice asks a session for you shows as a Voice message, marked with a waveform.",
    )
