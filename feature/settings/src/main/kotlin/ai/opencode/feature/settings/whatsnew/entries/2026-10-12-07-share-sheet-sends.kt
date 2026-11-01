package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.shareSheetSends: WhatsNewEntry
    get() = feature(
        id = "share-sheet-sends",
        day = "2026-10-12",
        title = "Send From the Share Sheet",
        symbol = "square.and.arrow.up",
        tint = Tint.blue,
        summary = "Share anything to OpenCode, pick a session, add a message, and send it without opening the app.",
        details = listOf(
            "Your recent sessions appear in the share sheet’s top row.",
            "JSON files and exports attach as files with their names intact.",
            "Continue in OpenCode opens the session with what you shared in the message box.",
            "If it can’t send, Send Later sends it the next time you open the app.",
        ),
        example = "Share a screenshot to OpenCode, type a question, and tap Send",
    )
