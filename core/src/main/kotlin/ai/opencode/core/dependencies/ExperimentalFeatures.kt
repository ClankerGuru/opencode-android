package ai.opencode.core.dependencies

import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared

/**
 * The experimental switches (iOS `Settings/ExperimentalFeatures.swift`: `ExperimentalFeature`,
 * `SharedKey.experimental(_:)`, `ExperimentalFeatures`). Moved down to `:core` because media,
 * browser and transcript code read them; `:feature:settings` owns the switches' titles, summaries
 * and grouping (extensions on [ExperimentalFeature]).
 */
enum class ExperimentalFeature(val rawValue: String) {
    // Declared in Settings order: grouped, then by importance within a group.
    ComposerAutocomplete("composer-autocomplete"),
    PlusMenuSheet("plus-menu-sheet"),
    AgentSelection("agent-selection"),
    HideModels("hide-models"),
    CallTray("call-tray"),
    FloatingCall("floating-call"),
    LivingOrb("living-orb"),
    NewMessagesDivider("new-messages-divider"),
    SinceYouLeft("since-you-left"),
    ImageLinkPreviews("image-link-previews"),
    ProjectIcons("project-icons"),
    SessionSwitcher("session-switcher"),
    ServerTitleSwitch("server-title-switch"),
    ServerDetails("server-details"),
    InAppBrowser("in-app-browser"),
    ServerVoice("server-voice"),
    BackgroundCallIntent("background-call-intent");

    val id: String get() = rawValue

    /** One experimental feature's switch (`experimental-<raw>`). Off by default. */
    val sharedKey: SharedKey<Boolean> get() = SharedKey.appStorage("experimental-$rawValue", false)

    companion object {
        fun fromRawValue(raw: String): ExperimentalFeature? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** The experimental switches as reducers read them, overridable in tests. */
class ExperimentalFeatures(val isEnabled: (ExperimentalFeature) -> Boolean) {
    operator fun invoke(feature: ExperimentalFeature): Boolean = isEnabled(feature)

    companion object Key : DependencyKey<ExperimentalFeatures> {
        override val liveValue: ExperimentalFeatures = ExperimentalFeatures { feature -> shared(feature.sharedKey).value }
        override val testValue: ExperimentalFeatures = ExperimentalFeatures { false }

        /** Every experiment on, or only [features]. */
        fun enabling(features: Set<ExperimentalFeature> = ExperimentalFeature.entries.toSet()) =
            ExperimentalFeatures { it in features }
    }
}
