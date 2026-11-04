package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.shareOpenedFiles: WhatsNewEntry
    get() = feature(
        id = "share-opened-files",
        day = "2026-10-10",
        title = "Images Like Photos",
        symbol = "photo.on.rectangle.angled",
        tint = Tint.pink,
        summary = "Images show whole in the session and open like Photos: pinch to zoom, swipe between them, and drag down to close.",
        details = listOf(
            "Every file the agent names opens in the app.",
            "Share saves anything you open to Photos or Files, videos and audio included.",
        ),
        example = "Ask the agent to read a few screenshots, then tap one and drag it down",
    )
