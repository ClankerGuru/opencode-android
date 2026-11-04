package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.orbDividerImageLinks: WhatsNewEntry
    get() = feature(
        id = "orb-divider-image-links",
        day = "2026-11-03",
        title = "Three Experiments Ship",
        symbol = "checkmark.seal.fill",
        tint = Tint.green,
        summary = "The Living Orb, the New divider and image link previews are now on for everyone, with no switch.",
        details = listOf(
            "Every call floats in the Living Orb; the old call bar is gone.",
            "Coming back to a session, a New divider marks what arrived while you were away, when it doesn’t fit on screen.",
            "Links straight to an image show the picture under the message.",
        ),
        example = "Start a call from the composer and watch it float in the orb",
    )
