package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.controlCenterSessions: WhatsNewEntry
    get() = feature(
        id = "control-center-sessions",
        day = "2026-10-14",
        title = "Tiles for a Session",
        symbol = "switch.2",
        tint = Tint.purple,
        summary = "Add a Quick Settings tile to open a session, or start a call in it, with one tap.",
        details = listOf(
            "The new Open Session tile opens the session you choose.",
            "Start Call can call into a session you choose, or a new session. Unless you change it, it calls the session you viewed last.",
            "Choose from your pinned and recent sessions; the tile shows the session’s current title.",
            "Tiles only open the app: nothing is sent until you speak or type.",
        ),
        example = "Open Quick Settings, tap Edit, and drag OpenCode’s Open Session tile into place",
    )
