package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceRouteConsistency: WhatsNewEntry
    get() = smaller(
        id = "voice-route-consistency",
        day = "2026-10-20",
        title = "Voice Connection Fixes",
        symbol = "phone",
        summary = "Grok keeps using your xAI key when your server runs calls, and Gemini identifies itself correctly.",
    )
