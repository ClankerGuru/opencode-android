package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.settingsGroupedByPurpose: WhatsNewEntry
    get() = smaller(
        id = "settings-grouped-by-purpose",
        day = "2026-10-14",
        title = "Clearer Settings",
        symbol = "gearshape",
        summary = "Find experimental features and developer tools in Settings → Experimental, in-app notices in Notifications, and call history in Voice.",
    )
