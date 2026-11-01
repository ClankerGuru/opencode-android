package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceVariedAcknowledgements: WhatsNewEntry
    get() = smaller(
        id = "voice-varied-acknowledgements",
        day = "2026-10-15",
        title = "Fewer “Checking”s on Calls",
        symbol = "waveform",
        summary = "On a call, the voice reacts to what you said instead of answering every request with “Checking”, and a request you keep talking over is no longer sent twice.",
    )
