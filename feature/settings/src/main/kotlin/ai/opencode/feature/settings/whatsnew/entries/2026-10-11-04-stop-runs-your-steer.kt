package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.stopRunsYourSteer: WhatsNewEntry
    get() = feature(
        id = "stop-runs-your-steer",
        day = "2026-10-11",
        title = "Steer While It Works",
        symbol = "arrow.turn.up.right",
        tint = Tint.indigo,
        summary = "Send while the agent works and it reads your message at its next step, moving long commands to the background first.",
        details = listOf(
            "Touch and hold Send to choose Steer or Queue for one message.",
            "Hold a waiting message to move it to the queue or delete it.",
            "Stop reads Stopping… at once, and a steering message you sent runs next.",
        ),
        example = "While a long command runs, send a message and watch the command move to the background",
    )
