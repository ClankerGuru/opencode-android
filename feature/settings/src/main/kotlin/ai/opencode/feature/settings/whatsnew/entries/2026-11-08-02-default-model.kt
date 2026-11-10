package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.defaultModel: WhatsNewEntry
    get() = smaller(
        id = "default-model",
        day = "2026-11-08",
        title = "New Sessions Use Your Default Model",
        symbol = "cpu",
        summary = "New sessions start on the model set as your default in OpenCode, at the thinking level you last picked for it, instead of the last session’s model.",
    )
