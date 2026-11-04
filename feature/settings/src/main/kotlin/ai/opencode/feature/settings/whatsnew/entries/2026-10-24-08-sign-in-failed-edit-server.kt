package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.signInFailedEditServer: WhatsNewEntry
    get() = smaller(
        id = "sign-in-failed-edit-server",
        day = "2026-10-24",
        title = "Fix a Refused Sign-In",
        symbol = "key",
        summary = "When a server refuses your password, the sidebar’s server menu offers Edit Server… instead of a Try Again that couldn’t help. The welcome screen now points to opencode pair --remote in the terminal.",
    )
