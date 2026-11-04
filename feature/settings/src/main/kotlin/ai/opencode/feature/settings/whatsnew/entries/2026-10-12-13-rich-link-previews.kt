package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.richLinkPreviews: WhatsNewEntry
    get() = feature(
        id = "rich-link-previews",
        day = "2026-10-12",
        title = "Link Previews",
        symbol = "link",
        tint = Tint.cyan,
        summary = "Links under a reply show what they are, one line each: a pull request or issue with its title and state, a commit, a video, a page.",
        details = listOf(
            "GitHub pull requests, issues, commits and repositories show their title, with Open, Merged or Closed in colour.",
            "X posts show who posted and what they said; YouTube links show their title, and other pages a name read from the link.",
            "Three links show; tap “more” beside Copy for the rest, and again to fold them away.",
            "Hold a link for a bigger preview, Copy Link, and Share.",
        ),
        example = "Ask the agent what changed in a repository this week",
        tryIt = Destination.Prompt("What changed in a GitHub repository I use this week? Include links."),
    )
