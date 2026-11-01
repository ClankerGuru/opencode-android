package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.ipadMultipleWindows: WhatsNewEntry
    get() = feature(
        id = "ipad-multiple-windows",
        day = "2026-10-21",
        title = "Multiple Windows on Tablets",
        symbol = "rectangle.split.2x1",
        tint = Tint.indigo,
        summary = "On a tablet, open sessions in windows of their own and keep two side by side.",
        details = listOf(
            "Hold a session in the sidebar and choose Open in New Window.",
            "Each window has its own session, sidebar and message box.",
            "Pins, read sessions and your servers are the same in every window.",
            "Windows come back on the session they showed.",
        ),
        example = "Hold a session in the sidebar, choose Open in New Window, and put it beside the first.",
    )
