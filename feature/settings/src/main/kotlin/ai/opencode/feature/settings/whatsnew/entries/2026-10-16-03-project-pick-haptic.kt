package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.projectPickHaptic: WhatsNewEntry
    get() = smaller(
        id = "project-pick-haptic",
        day = "2026-10-16",
        title = "A Tick for a New Project",
        symbol = "folder",
        summary = "Choosing a different project for a new session, above the message box or in All Projects, gives a light tick.",
    )
