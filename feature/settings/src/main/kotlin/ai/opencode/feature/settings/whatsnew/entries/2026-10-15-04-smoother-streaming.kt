package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.smootherStreaming: WhatsNewEntry
    get() = smaller(
        id = "smoother-streaming",
        day = "2026-10-15",
        title = "Smoother Streaming Replies",
        symbol = "text.line.last.and.arrowtriangle.forward",
        summary = "A streaming reply’s newest line fades in without dimming or flicking on, rows no longer stretch past their text and spring back, and list items and headings appear as themselves instead of starting as a stray dash or hash.",
    )
