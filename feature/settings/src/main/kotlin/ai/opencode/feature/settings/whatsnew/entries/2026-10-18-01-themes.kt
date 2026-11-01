package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.themes: WhatsNewEntry
    get() = feature(
        id = "themes",
        day = "2026-10-18",
        title = "Themes",
        symbol = "paintpalette",
        tint = Tint.purple,
        summary = "Give sessions a theme, like Tokyo Night, Catppuccin, Dracula, Rosé Pine or GitHub, with a live preview as you browse.",
        details = listOf(
            "Themes colour the transcript, your messages, code, syntax, diffs and links.",
            "Mode in Appearance sets light, dark or system.",
            "Controls keep their native Liquid Glass look in every theme.",
        ),
        example = "Open Settings → Appearance → Theme and try Tokyo Night.",
        tryIt = Destination.Open(SettingsDestination.Themes),
    )
