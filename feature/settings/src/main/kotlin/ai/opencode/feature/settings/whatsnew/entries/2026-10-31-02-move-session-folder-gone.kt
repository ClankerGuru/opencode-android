package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.moveSessionFolderGone: WhatsNewEntry
    get() = smaller(
        id = "move-session-folder-gone",
        day = "2026-10-31",
        title = "Sessions Outlive Their Folder",
        symbol = "folder",
        summary = "When a session’s folder is gone, like a removed worktree, a failed send offers to move it to its project’s folder and sends again.",
    )
