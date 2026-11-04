package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.gptLiveOwnKey: WhatsNewEntry
    get() = smaller(
        id = "gpt-live-own-key",
        day = "2026-10-23",
        title = "GPT-Live Keys",
        symbol = "key",
        summary = "GPT-Live calls use your own OpenAI key. With Server Voice on, they can use your server’s OpenAI key or its OpenCode Console connection instead.",
    )
