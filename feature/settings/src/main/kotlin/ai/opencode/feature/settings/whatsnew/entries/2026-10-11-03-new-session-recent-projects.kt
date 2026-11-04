package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.newSessionRecentProjects: WhatsNewEntry
    get() = feature(
        id = "new-session-recent-projects",
        day = "2026-10-11",
        title = "Start in Any Project",
        symbol = "folder",
        tint = Tint.teal,
        summary = "New Session lists your recent projects above the message box, and All Projects can open any folder on your computer.",
        details = listOf(
            "The project you chose stays above the message box as you type.",
            "A call started from a new session works in the project you chose.",
        ),
        example = "Tap New Session, then All Projects, then Choose Folder…",
        tryIt = Destination.NewSession,
    )
