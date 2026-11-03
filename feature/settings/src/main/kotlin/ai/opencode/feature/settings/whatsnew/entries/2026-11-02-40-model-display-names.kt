package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.modelDisplayNames: WhatsNewEntry
    get() = smaller(
        id = "model-display-names",
        day = "2026-11-02",
        title = "Model Names",
        symbol = "textformat",
        summary = "The composer remembers model names while the model list loads or reconnects.",
    )
