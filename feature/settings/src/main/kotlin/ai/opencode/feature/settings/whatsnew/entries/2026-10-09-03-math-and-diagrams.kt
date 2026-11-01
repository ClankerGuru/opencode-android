package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.mathAndDiagrams: WhatsNewEntry
    get() = feature(
        id = "math-and-diagrams",
        day = "2026-10-09",
        title = "Math and Diagrams",
        symbol = "function",
        tint = Tint.indigo,
        summary = "Replies typeset math and draw Mermaid diagrams.",
        details = listOf(
            "Tap a diagram to zoom in, or hold it to see its source.",
            "Math follows your text size.",
        ),
        example = "Ask for a flowchart of how a message is sent",
        tryIt = Destination.Prompt("Draw a flowchart of how a message is sent"),
    )
