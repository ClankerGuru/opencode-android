package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.markdownStreamsSmoothly: WhatsNewEntry
    get() = smaller(
        id = "markdown-streams-smoothly",
        day = "2026-10-20",
        title = "Smoother Streaming Replies",
        symbol = "text.alignleft",
        summary = "Long replies stay quick as they stream, and half-written bold, code and links show formatted instead of as raw Markdown.",
    )
