package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.onePicker: WhatsNewEntry
    get() = feature(
        id = "one-picker",
        day = "2026-11-07",
        title = "New Model Picker",
        symbol = "cpu",
        tint = Tint.purple,
        summary = "Tap the model in the message box and the list grows out of it, with Favorites and Recent at the top and every provider below.",
        details = listOf(
            "Tap the thinking level on a model’s row, like “high”, to change it without leaving the list.",
            "Swipe or hold a row to add it to Favorites or take it out.",
            "Search is at the bottom, and typing a level after a name, like “opus high”, picks both when you press Return.",
            "Drag the grabber up for a taller list, or down to close it.",
            "The new picker replaces the old Model sheet, and the One Picker switch is gone from Settings › Experimental.",
        ),
        example = "Tap the model in the message box, then swipe a row to favorite it",
        tryIt = Destination.ModelList,
    )
