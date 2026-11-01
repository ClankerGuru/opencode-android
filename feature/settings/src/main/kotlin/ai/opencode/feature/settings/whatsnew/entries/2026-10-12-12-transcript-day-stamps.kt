package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.transcriptDayStamps: WhatsNewEntry
    get() = feature(
        id = "transcript-day-stamps",
        day = "2026-10-12",
        title = "Days in the Transcript",
        symbol = "calendar",
        tint = Tint.red,
        summary = "A session that runs across days shows the day over the first message of each one, like “Wednesday 8:24 AM”, and the time over a message sent after an hour’s pause.",
        details = listOf(
            "Days read Today, Yesterday, or the weekday within the week, otherwise the date.",
            "Steering and queued messages get no stamp while they wait.",
        ),
        example = "Open a session you started earlier in the week and scroll up",
    )
