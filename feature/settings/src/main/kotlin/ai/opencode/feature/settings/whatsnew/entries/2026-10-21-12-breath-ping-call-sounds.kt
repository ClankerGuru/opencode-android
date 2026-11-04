package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.breathPingCallSounds: WhatsNewEntry
    get() = smaller(
        id = "breath-ping-call-sounds",
        day = "2026-10-21",
        title = "Softer Call Sounds",
        symbol = "speaker.wave.1",
        summary = "Calls connect, ping and hang up with soft, breathy tones that sit quietly under the conversation.",
    )
