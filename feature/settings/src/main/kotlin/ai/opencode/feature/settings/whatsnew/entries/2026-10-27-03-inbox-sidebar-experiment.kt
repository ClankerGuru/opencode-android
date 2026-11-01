package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.inboxSidebarExperiment: WhatsNewEntry
    get() = smaller(
        id = "inbox-sidebar-experiment",
        day = "2026-10-27",
        title = "Inbox Sidebar",
        symbol = "tray",
        summary = "Turn on Inbox Sidebar in Settings › Experimental › Drawer to see your sessions as an inbox: what needs your input first, then unread, each with its project and latest reply. Swipe to mark one read or archive it.",
        tryIt = Destination.Open(SettingsDestination.Experiment("inbox-sidebar")),
    )
