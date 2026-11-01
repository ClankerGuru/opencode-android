package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.rideOutDrops: WhatsNewEntry
    get() = feature(
        id = "ride-out-drops",
        day = "2026-10-11",
        title = "Works on Weak Signal",
        symbol = "wifi",
        tint = Tint.mint,
        summary = "A few seconds without signal no longer fails anything. Sends, sessions, and calls wait for the connection and carry on.",
        details = listOf(
            "Send is always ready, and messages go in the order you sent them.",
            "A message that couldn’t send stays in place with Retry, and retrying never sends it twice.",
            "When your server is offline, its name in the drawer dims. Tap it for Try Again.",
        ),
        example = "Open a session as your train goes into a tunnel",
    )
