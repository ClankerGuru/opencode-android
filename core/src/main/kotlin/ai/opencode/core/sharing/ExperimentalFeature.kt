package ai.opencode.core.sharing

import ai.opencode.core.dependencies.DependencyKey

/**
 * Port of iOS `ExperimentalFeature` and `ExperimentalFeatures` (`OpenCode/Settings/ExperimentalFeatures.swift`):
 * features still being tried out on phones, listed in Settings › Advanced, off by default, kept
 * on this phone. Lives in `:core` because Composer, Transcript, Voice, Drawer and Settings all
 * read it; Settings owns the groups, Call Style and the list UI.
 *
 * Code reads it with `dependency(ExperimentalFeatures)(feature)` (reducers) or
 * `shared(experimental(feature))` (views).
 */
@Suppress("EnumEntryName")
enum class ExperimentalFeature(val rawValue: String, val title: String, val summary: String, val symbol: String) {
    // Declared in Settings order: grouped, then by importance within a group.
    composerAutocomplete("composer-autocomplete", "Composer Autocomplete", "Grow composer for @ and /", "at"),
    plusMenuSheet("plus-menu-sheet", "New Plus Menu", "Photos in place of the keyboard", "plus.circle"),
    agentSelection("agent-selection", "Agent Selection", "Choose Build, Plan, or your own", "person.crop.circle"),
    hideModels("hide-models", "Hide Unused Models", "Choose models the picker lists", "eye.slash"),
    callTray("call-tray", "Call Tray", "Call scope beneath the composer", "rectangle.bottomhalf.inset.filled"),
    floatingCall("floating-call", "Floating Call", "Keep calls in a floating orb", "phone.circle.fill"),
    livingOrb("living-orb", "Living Orb", "Voice-reactive phosphor with state colours", "circle.hexagongrid.fill"),
    newMessagesDivider("new-messages-divider", "New Messages Divider", "Mark long unread replies", "text.line.first.and.arrowtriangle.forward"),
    sinceYouLeft("since-you-left", "Since You Left", "Recap activity since you left", "clock.arrow.circlepath"),
    imageLinkPreviews("image-link-previews", "Image Link Previews", "Load linked images in replies", "photo"),
    projectIcons("project-icons", "Project Icons", "Project icons beside session titles", "app.dashed"),
    sessionSwitcher("session-switcher", "Session Switcher", "Swipe sideways or up through sessions", "square.stack"),
    serverTitleSwitch("server-title-switch", "Title Is the Switch", "Connect and switch from the title", "server.rack"),
    serverDetails("server-details", "Server Details", "MCP servers, skills, and accounts", "server.rack"),
    inAppBrowser("in-app-browser", "In-App Browser", "Open web pages inside a session", "safari"),
    serverVoice("server-voice", "Server Voice", "Server-key calls and Gemini Live", "waveform"),
    backgroundCallIntent("background-call-intent", "Background Action Button Call", "Calls start without opening the app", "button.programmable"),
    voiceSessionDelegator("voice-session-delegator", "OpenCode Session Delegator", "Delegate calls to an OpenCode session", "bubble.left.and.bubble.right"),
    ;

    /** Whether this experiment is on in a view, from its stored switch. */
    fun isActive(stored: Boolean): Boolean = stored

    companion object {
        fun fromRawValue(rawValue: String): ExperimentalFeature? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** One experimental feature's switch (iOS `.experimental(feature)`). Off by default. */
fun experimental(feature: ExperimentalFeature): SharedKey<Boolean> =
    SharedKey.appStorage("experimental-${feature.rawValue}", false)

/** The experimental switches as reducers read them, overridable in tests. */
class ExperimentalFeatures(val isEnabled: (ExperimentalFeature) -> Boolean) {
    operator fun invoke(feature: ExperimentalFeature): Boolean = isEnabled(feature)

    companion object Key : DependencyKey<ExperimentalFeatures> {
        override val liveValue = ExperimentalFeatures { feature -> shared(experimental(feature)).value }
        override val testValue = ExperimentalFeatures { false }

        /** Every experiment on, or only [features]. */
        fun enabling(features: Set<ExperimentalFeature> = ExperimentalFeature.entries.toSet()) =
            ExperimentalFeatures { it in features }
    }
}
