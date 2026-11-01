package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.quoteReplies: WhatsNewEntry
    get() = smaller(
        id = "quote-replies",
        day = "2026-10-18",
        title = "Quote Replies",
        symbol = "text.quote",
        summary = "Select text in an assistant reply and tap Quote to insert it into your message as a Markdown blockquote.",
    )
