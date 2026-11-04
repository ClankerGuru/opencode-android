package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.welcomePreview: WhatsNewEntry
    get() = smaller(
        id = "welcome-preview",
        day = "2026-10-28",
        title = "Welcome Preview",
        symbol = "hand.wave",
        summary = "A new first-run welcome shows OpenCode working on a laptop while a phone steers it, then pairs in one step. Turn on Welcome Preview in Settings › Experimental › Drawer and tap Show Welcome to see it; closing it brings you back.",
    )
