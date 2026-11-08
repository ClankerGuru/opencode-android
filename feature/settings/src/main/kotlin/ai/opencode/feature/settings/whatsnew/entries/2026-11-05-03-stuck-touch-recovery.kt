package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.stuckTouchRecovery: WhatsNewEntry
    get() = smaller(
        id = "stuck-touch-recovery",
        day = "2026-11-05",
        title = "Scrolling Recovers Itself",
        symbol = "hand.point.up.left",
        summary = "If something invisible keeps a session from scrolling for more than a second, the app releases it.",
    )
