package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.modelListAndLevelMeter: WhatsNewEntry
    get() = feature(
        id = "model-list-and-level-meter",
        day = "2026-10-12",
        title = "New Model Picker",
        symbol = "cpu",
        tint = Tint.purple,
        summary = "Choose a model and its thinking level from the Model sheet, with your favorites on top. To try the list that grows out of the message box, turn on New Model Picker in Settings → Experimental.",
        details = listOf(
            "Use the Thinking menu in the Model sheet when a model offers levels.",
            "Swipe a model to add it to Favorites.",
            "A quiet line in the session marks where you switched model or level.",
        ),
        example = "Tap the model in the message box and choose a thinking level",
        tryIt = Destination.ModelList,
    )
