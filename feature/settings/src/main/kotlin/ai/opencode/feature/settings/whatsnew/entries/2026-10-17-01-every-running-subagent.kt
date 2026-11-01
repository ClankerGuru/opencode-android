package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.everyRunningSubagent: WhatsNewEntry
    get() = smaller(
        id = "every-running-subagent",
        day = "2026-10-17",
        title = "Every Running Subagent",
        symbol = "person.2",
        summary = "The subagent count above the composer includes ones nested under other subagents and ones in sessions with more than 100, matching the TUI.",
    )
