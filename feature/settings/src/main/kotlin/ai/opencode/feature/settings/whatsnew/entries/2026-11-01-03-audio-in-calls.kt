package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.audioInCalls: WhatsNewEntry
    get() = smaller(
        id = "audio-in-calls",
        day = "2026-11-01",
        title = "Audio in Calls",
        symbol = "music.note",
        summary = "Turn on Audio in Calls in Settings › Experimental › Calls, and during a call an agent can play music or a clip for you. It turns down while either of you speaks; pause or stop it above the composer.",
    )
