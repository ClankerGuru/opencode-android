package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.newSessionProjectContinuity: WhatsNewEntry
    get() = smaller(
        id = "new-session-project-continuity",
        day = "2026-10-20",
        title = "Steadier Project Picking",
        symbol = "folder",
        summary = "Recent folders keep their places as you choose a project, the header name changes softly, and the Project sheet has clearer folder paths.",
    )
