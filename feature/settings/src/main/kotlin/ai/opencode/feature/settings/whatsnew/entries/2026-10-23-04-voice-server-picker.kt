package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceServerPicker: WhatsNewEntry
    get() = smaller(
        id = "voice-server-picker",
        day = "2026-10-23",
        title = "Choose a Voice Server",
        symbol = "server.rack",
        summary = "In Settings › Voice › Voice Provider, choose which saved server your calls go through, or your own OpenAI account on this phone.",
    )
