package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.projectIconsCurrentDiscovery: WhatsNewEntry
    get() = smaller(
        id = "project-icons-current-discovery",
        day = "2026-10-22",
        title = "Project Icon Fixes",
        symbol = "app",
        summary = "With Project Icons enabled, the sidebar also shows icons your server finds in Icon Composer bundles.",
    )
