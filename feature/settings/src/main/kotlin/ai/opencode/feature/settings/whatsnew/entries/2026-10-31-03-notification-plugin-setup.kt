package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.notificationPluginSetup: WhatsNewEntry
    get() = smaller(
        id = "notification-plugin-setup",
        day = "2026-10-31",
        title = "Set Up Notifications with OpenCode",
        symbol = "bell.badge",
        summary = "In Settings → Notifications, tap Set Up with OpenCode to review a prompt that installs the missing notification plugin.",
        tryIt = Destination.notifications,
    )
