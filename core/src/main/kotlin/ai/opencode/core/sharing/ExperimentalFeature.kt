package ai.opencode.core.sharing

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.experiments.ExperimentalGroup

/**
 * Port of iOS `ExperimentalFeature` (`OpenCode/Settings/Experimental/ExperimentalFeature.swift`)
 * and `ExperimentalFeatures` (`OpenCode/Settings/ExperimentalFeatures.swift`): features still
 * being tried out on phones, listed in Settings › Experimental, off by default (a graduated one
 * on, [defaultEnabled]), kept on this phone. Lives in `:core` because Composer, Transcript, Voice,
 * Drawer and Settings all read it; Settings owns the Call Style and the list UI.
 *
 * iOS declares each experiment in its own `Experimental/Features/<Name>Experiment.swift` and
 * generates the registry at build time (`scripts/registries.py`); this enum is that registry, one
 * entry per iOS file with the file's fields, declared in [order]. [allCases] is iOS `allCases`.
 *
 * Code reads it with `dependency(ExperimentalFeatures)(feature)` (reducers) or
 * `shared(experimental(feature))` (views).
 */
@Suppress("EnumEntryName")
enum class ExperimentalFeature(
    /** The switch's key (`experimental-<rawValue>`) and lab name. */
    val rawValue: String,
    val title: String,
    /** Settings section the switch sits in. */
    val group: ExperimentalGroup,
    /** A few words under the title, one line. */
    val summary: String,
    /** SF Symbol name (iOS `systemImage`), drawn through `OpenCodeIcons`. */
    val symbol: String,
    /** Settings position: lower first. Leave gaps (10, 20, …) so a new one can slot between. */
    val order: Int,
    /** On until switched off: graduated to everyone but keeps its switch. */
    val defaultEnabled: Boolean = false,
    /**
     * Graduated: its switch left Experimental for a regular Settings page (New Messages Divider →
     * Settings › Sessions). It keeps its `experimental-…` key, so a saved choice carries over.
     */
    val isGraduated: Boolean = false,
    /**
     * Removed on iOS but still read by modules whose port of the removal hasn't landed. Not in
     * [allCases], never listed, never on (Floating Call reads Living Orb's switch, see [storedAs]).
     */
    val isRetired: Boolean = false,
) {
    composerAutocomplete("composer-autocomplete", "Composer Autocomplete", ExperimentalGroup.composer, "Grow composer for @ and /", "at", order = 10),
    plusMenuSheet("plus-menu-sheet", "New Plus Menu", ExperimentalGroup.composer, "Photos grow from the + menu", "plus.circle", order = 20),
    agentSelection("agent-selection", "Agent Selection", ExperimentalGroup.composer, "Choose Build, Plan, or your own", "person.crop.circle", order = 30),
    hideModels("hide-models", "Hide Unused Models", ExperimentalGroup.composer, "Choose models the picker lists", "eye.slash", order = 40),
    callTray("call-tray", "Call Tray", ExperimentalGroup.calls, "Call scope beneath the composer", "rectangle.bottomhalf.inset.filled", order = 50),
    livingOrb("living-orb", "Living Orb", ExperimentalGroup.calls, "Keep calls in a floating orb", "circle.hexagongrid.fill", order = 60),
    newMessagesDivider(
        "new-messages-divider", "New Messages Divider", ExperimentalGroup.transcript, "Mark long unread replies",
        "text.line.first.and.arrowtriangle.forward", order = 70, defaultEnabled = true, isGraduated = true,
    ),
    imageLinkPreviews("image-link-previews", "Image Link Previews", ExperimentalGroup.transcript, "Load linked images in replies", "photo", order = 80),
    inboxSidebar("inbox-sidebar", "Inbox Sidebar", ExperimentalGroup.drawer, "Sessions as an inbox", "tray", order = 90),
    projectIcons("project-icons", "Project Icons", ExperimentalGroup.drawer, "Project icons beside session titles", "app.dashed", order = 100, defaultEnabled = true),
    serverDetails("server-details", "Server Details", ExperimentalGroup.drawer, "MCP servers, skills, and accounts", "server.rack", order = 110),
    serverFlow("server-flow", "Server Flow", ExperimentalGroup.drawer, "Swap servers at once, with status", "arrow.left.arrow.right", order = 120),
    welcomePreview("welcome-preview", "Welcome Preview", ExperimentalGroup.drawer, "The new first-run welcome", "hand.wave", order = 130),
    sessionLiveActivity(
        "session-live-activity", "Session Live Activity", ExperimentalGroup.drawer, "Follow runs on the Lock Screen",
        "platter.filled.top.iphone", order = 140,
    ),
    soundEffects("sound-effects", "Sound Effects", ExperimentalGroup.drawer, "Soft sounds for replies and requests", "speaker.wave.2.fill", order = 150),
    inAppBrowser("in-app-browser", "In-App Browser", ExperimentalGroup.transcript, "Open web pages inside a session", "safari", order = 160),
    serverVoice("server-voice", "Server Voice", ExperimentalGroup.calls, "Server-key calls and Gemini Live", "waveform", order = 170),
    backgroundCallIntent(
        "background-call-intent", "Background Action Button Call", ExperimentalGroup.calls, "Calls start without opening the app",
        "button.programmable", order = 180,
    ),
    voiceSessionDelegator(
        "voice-session-delegator", "OpenCode Session Delegator", ExperimentalGroup.calls, "Delegate calls to an OpenCode session",
        "bubble.left.and.bubble.right", order = 190,
    ),

    // Removed on iOS; kept only until the modules that still read them port the removal.

    /** iOS 64ff3ed7/e4aeb212: Living Orb is the floating call. Reads Living Orb's switch ([storedAs]). */
    @Deprecated("Removed on iOS (64ff3ed7): read livingOrb")
    floatingCall("floating-call", "Floating Call", ExperimentalGroup.calls, "Keep calls in a floating orb", "phone.circle.fill", order = Int.MAX_VALUE, isRetired = true),

    /** iOS cd6be7c6 removed the Since You Left experiment. Never on. */
    @Deprecated("Removed on iOS (cd6be7c6)")
    sinceYouLeft("since-you-left", "Since You Left", ExperimentalGroup.transcript, "Recap activity since you left", "clock.arrow.circlepath", order = Int.MAX_VALUE, isRetired = true),

    /** iOS c75d4a1e removed the Session Switcher experiment. Never on; its switch is cleared at launch. */
    @Deprecated("Removed on iOS (c75d4a1e)")
    sessionSwitcher("session-switcher", "Session Switcher", ExperimentalGroup.drawer, "Swipe sideways or up through sessions", "square.stack", order = Int.MAX_VALUE, isRetired = true),

    /** iOS c75d4a1e removed Title Is the Switch. Never on; its switch is cleared at launch. */
    @Deprecated("Removed on iOS (c75d4a1e)")
    serverTitleSwitch("server-title-switch", "Title Is the Switch", ExperimentalGroup.drawer, "Connect and switch from the title", "server.rack", order = Int.MAX_VALUE, isRetired = true),
    ;

    val id: String get() = rawValue

    /** The experiment whose switch this one reads: itself, or the one a retired experiment became. */
    @Suppress("DEPRECATION")
    val storedAs: ExperimentalFeature get() = if (this == floatingCall) livingOrb else this

    /** Retired with nothing to read: always off. */
    private val isOff: Boolean get() = isRetired && storedAs == this

    /** Whether this experiment is on in a view, from its stored switch (`shared(experimental(this))`). */
    fun isActive(stored: Boolean): Boolean = if (isOff) false else stored

    companion object {
        /** Every experiment, in Settings order ([order], then key). Retired ones aside. */
        val allCases: List<ExperimentalFeature> by lazy {
            entries.filter { !it.isRetired }.sortedWith(compareBy({ it.order }, { it.rawValue }))
        }

        /** The registered experiment with this key, or null (iOS `init?(rawValue:)`). */
        fun fromRawValue(rawValue: String): ExperimentalFeature? = allCases.firstOrNull { it.rawValue == rawValue }

        /**
         * Switches of removed experiments (Session Switcher, Title Is the Switch). Nothing reads
         * them; they're cleared at launch.
         */
        val retiredKeys = listOf("experimental-session-switcher", "experimental-server-title-switch")

        fun removeRetiredSwitches(store: SharedStore) {
            for (key in retiredKeys) store.remove(SharedKey.appStorage(key, false))
        }
    }
}

/**
 * One experimental feature's switch (iOS `.experimental(feature)`): `experimental-<rawValue>`,
 * off by default unless it has graduated ([ExperimentalFeature.defaultEnabled]).
 */
fun experimental(feature: ExperimentalFeature): SharedKey<Boolean> = feature.storedAs.let {
    SharedKey.appStorage("experimental-${it.rawValue}", it.defaultEnabled)
}

/** The experimental switches as reducers read them, overridable in tests. */
class ExperimentalFeatures(val isEnabled: (ExperimentalFeature) -> Boolean) {
    operator fun invoke(feature: ExperimentalFeature): Boolean = isEnabled(feature)

    companion object Key : DependencyKey<ExperimentalFeatures> {
        override val liveValue = ExperimentalFeatures { feature -> feature.isActive(shared(experimental(feature)).value) }
        override val testValue = ExperimentalFeatures { false }

        /** Every experiment on, or only [features]. */
        fun enabling(features: Set<ExperimentalFeature> = ExperimentalFeature.allCases.toSet()) =
            ExperimentalFeatures { it in features }
    }
}
