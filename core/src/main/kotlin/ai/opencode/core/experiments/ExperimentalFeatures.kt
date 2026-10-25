package ai.opencode.core.experiments

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Port of iOS `OpenCode/Settings/ExperimentalFeatures.swift` (moved down to `:core` because
 * every feature module reads it; see docs/parity/settings.md).
 *
 * Features still being tried out on phones: listed in Settings → Advanced, off by default,
 * and kept on this phone. Adding one is a case here (with its title, summary, symbol and
 * group); Settings lists every case, and code reads it with `dependency(ExperimentalFeatures)`
 * or `shared(ExperimentalFeature.x.key)`. Separate from Developer Mode, which uploads
 * diagnostics.
 *
 * Declared in Settings order: grouped, then by importance within a group.
 */
enum class ExperimentalFeature(val rawValue: String) {
    composerAutocomplete("composer-autocomplete"),
    plusMenuSheet("plus-menu-sheet"),
    agentSelection("agent-selection"),
    hideModels("hide-models"),
    callTray("call-tray"),
    floatingCall("floating-call"),
    livingOrb("living-orb"),
    newMessagesDivider("new-messages-divider"),
    sinceYouLeft("since-you-left"),
    imageLinkPreviews("image-link-previews"),
    projectIcons("project-icons"),
    sessionSwitcher("session-switcher"),
    serverTitleSwitch("server-title-switch"),
    serverDetails("server-details"),
    inAppBrowser("in-app-browser"),
    serverVoice("server-voice"),
    backgroundCallIntent("background-call-intent"),
    ;

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            inAppBrowser -> "In-App Browser"
            newMessagesDivider -> "New Messages Divider"
            agentSelection -> "Agent Selection"
            hideModels -> "Hide Unused Models"
            floatingCall -> "Floating Call"
            livingOrb -> "Living Orb"
            callTray -> "Call Tray"
            plusMenuSheet -> "New Plus Menu"
            imageLinkPreviews -> "Image Link Previews"
            projectIcons -> "Project Icons"
            sessionSwitcher -> "Session Switcher"
            serverTitleSwitch -> "Title Is the Switch"
            sinceYouLeft -> "Since You Left"
            composerAutocomplete -> "Composer Autocomplete"
            serverVoice -> "Server Voice"
            backgroundCallIntent -> "Background Action Button Call"
            serverDetails -> "Server Details"
        }

    /** Settings section the switch sits in. */
    val group: ExperimentalGroup
        get() = when (this) {
            composerAutocomplete, plusMenuSheet, agentSelection, hideModels -> ExperimentalGroup.composer
            callTray, floatingCall, livingOrb -> ExperimentalGroup.calls
            newMessagesDivider, sinceYouLeft, imageLinkPreviews -> ExperimentalGroup.transcript
            projectIcons, sessionSwitcher, serverTitleSwitch, serverDetails -> ExperimentalGroup.drawer
            inAppBrowser -> ExperimentalGroup.transcript
            serverVoice, backgroundCallIntent -> ExperimentalGroup.calls
        }

    /** A few words under the title, one line. */
    val summary: String
        get() = when (this) {
            composerAutocomplete -> "Grow composer for @ and /"
            plusMenuSheet -> "Photos in place of the keyboard"
            agentSelection -> "Choose Build, Plan, or your own"
            hideModels -> "Choose models the picker lists"
            callTray -> "Call scope beneath the composer"
            floatingCall -> "Keep calls in a floating orb"
            livingOrb -> "Voice-reactive phosphor with state colours"
            newMessagesDivider -> "Mark long unread replies"
            sinceYouLeft -> "Recap activity since you left"
            imageLinkPreviews -> "Load linked images in replies"
            projectIcons -> "Project icons beside session titles"
            sessionSwitcher -> "Swipe sideways or up through sessions"
            serverTitleSwitch -> "Connect and switch from the title"
            inAppBrowser -> "Open web pages inside a session"
            serverVoice -> "Server-key calls and Gemini Live"
            backgroundCallIntent -> "Calls start without opening the app"
            serverDetails -> "MCP servers, skills, and accounts"
        }

    /** SF Symbol name (draw with `OpenCodeIcon(sfName, …)`). */
    val systemImage: String
        get() = when (this) {
            inAppBrowser -> "safari"
            newMessagesDivider -> "text.line.first.and.arrowtriangle.forward"
            agentSelection -> "person.crop.circle"
            hideModels -> "eye.slash"
            floatingCall -> "phone.circle.fill"
            livingOrb -> "circle.hexagongrid.fill"
            callTray -> "rectangle.bottomhalf.inset.filled"
            plusMenuSheet -> "plus.circle"
            imageLinkPreviews -> "photo"
            projectIcons -> "app.dashed"
            sessionSwitcher -> "square.stack"
            serverTitleSwitch -> "server.rack"
            sinceYouLeft -> "clock.arrow.circlepath"
            composerAutocomplete -> "at"
            serverVoice -> "waveform"
            backgroundCallIntent -> "button.programmable"
            serverDetails -> "server.rack"
        }

    /** One experimental feature's switch (iOS `.experimental(feature)`): `experimental-<raw>`, off by default. */
    val key: SharedKey<Boolean> get() = keys.getValue(this)

    /**
     * Whether this experiment is on in a view. iOS also honours `OPENCODE_LAB_EXPERIMENTS` in
     * DEBUG lab runs; Android has no launch environment, so the stored switch decides.
     */
    fun isActive(stored: Boolean): Boolean = stored

    /** Written by Settings' Call Style menu rather than shown as a switch. */
    val isCallStyle: Boolean get() = ExperimentalCallStyle.entries.any { it.feature == this }

    companion object {
        fun fromRaw(raw: String): ExperimentalFeature? = entries.firstOrNull { it.rawValue == raw }

        private val keys: Map<ExperimentalFeature, SharedKey<Boolean>> =
            entries.associateWith { SharedKey.appStorage("experimental-${it.rawValue}", false) }
    }
}

/** The sections of Settings › Advanced's experiments, in order, all open. */
enum class ExperimentalGroup {
    composer, calls, transcript, drawer;

    val id: String get() = name

    val title: String
        get() = when (this) {
            composer -> "Composer"
            calls -> "Calls"
            transcript -> "Transcript"
            drawer -> "Drawer"
        }

    val features: List<ExperimentalFeature> get() = ExperimentalFeature.entries.filter { it.group == this }

    /** The group's switches: every feature but those a style menu writes (Call Style's). */
    val toggles: List<ExperimentalFeature> get() = features.filter { !it.isCallStyle }
}

/**
 * Settings' one Call Style choice, stored as the two call experiments so saved switches
 * carry over. Call Tray wins when both are on, as in `SessionView`.
 */
enum class ExperimentalCallStyle {
    standard, floating, tray;

    val id: String get() = name
    val callTray: Boolean get() = this == tray
    val floatingCall: Boolean get() = this == floating

    /** The experiment this style turns on. */
    val feature: ExperimentalFeature?
        get() = when (this) {
            standard -> null
            floating -> ExperimentalFeature.floatingCall
            tray -> ExperimentalFeature.callTray
        }

    val title: String
        get() = when (this) {
            standard -> "Standard"
            floating -> "Floating"
            tray -> "Tray"
        }

    val summary: String
        get() = when (this) {
            standard -> "The built-in call view"
            floating -> ExperimentalFeature.floatingCall.summary
            tray -> ExperimentalFeature.callTray.summary
        }

    companion object {
        fun of(callTray: Boolean, floatingCall: Boolean, livingOrb: Boolean = false): ExperimentalCallStyle =
            if (livingOrb) floating else if (callTray) tray else if (floatingCall) floating else standard
    }
}

/**
 * Settings' one Model Picker choice: the model chip opens today's menu, a stock sheet with
 * each model's level, or the composer grows into the custom picker (WIP). Replaced the New
 * Model Picker and Custom Model Picker switches; [migrateLegacySwitches] carries them over.
 */
@Serializable
enum class ModelPickerStyle(val rawValue: String) {
    @SerialName("today") today("today"),
    @SerialName("sheet") sheet("sheet"),
    @SerialName("grow-from-composer") growFromComposer("grow-from-composer");

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            today -> "Today"
            sheet -> "Sheet"
            growFromComposer -> "Grow from Composer"
        }

    val summary: String
        get() = when (this) {
            today -> "The built-in model menu"
            sheet -> "Pick a model in a sheet"
            growFromComposer -> "Composer grows into a model picker"
        }

    companion object {
        const val STORAGE_KEY = "experimental-model-picker"
        const val LEGACY_SHEET_KEY = "experimental-new-model-picker"
        const val LEGACY_GROW_KEY = "experimental-custom-model-picker"

        /** The style the old switches meant. Custom Model Picker won when both were on. */
        fun of(newModelPicker: Boolean, customModelPicker: Boolean): ModelPickerStyle =
            if (customModelPicker) growFromComposer else if (newModelPicker) sheet else today

        /**
         * Moves the old switches into the style once, then removes them. A style already chosen
         * stays. Android never shipped the legacy switches, so callers pass what they read
         * (null when absent) and apply the returned style, if any.
         */
        fun migrateLegacySwitches(
            storedStyle: ModelPickerStyle?,
            legacySheet: Boolean?,
            legacyGrow: Boolean?,
        ): ModelPickerStyle? {
            if (legacySheet == null && legacyGrow == null) return null
            if (storedStyle != null) return null
            val style = of(legacySheet == true, legacyGrow == true)
            return style.takeIf { it != today }
        }

        /** The style in use. iOS lab runs may override it; Android uses the saved one. */
        fun active(stored: ModelPickerStyle): ModelPickerStyle = stored

        /**
         * `model-picker=sheet` (or `=grow-from-composer`, `=today`) in an experiments list; the
         * old `new-model-picker` and `custom-model-picker` names still pick Sheet and Grow from
         * Composer.
         */
        fun labStyle(experiments: String): ModelPickerStyle? {
            val names = experiments.split(",").map { it.trim() }.toSet()
            names.firstNotNullOfOrNull { name ->
                if (!name.startsWith("model-picker=")) null
                else entries.firstOrNull { it.rawValue == name.removePrefix("model-picker=") }
            }?.let { return it }
            if ("custom-model-picker" in names) return growFromComposer
            if ("new-model-picker" in names) return sheet
            return null
        }

        /** Settings › Advanced › Model Picker. Today by default. */
        val key: SharedKey<ModelPickerStyle> = SharedKey.appStorage(STORAGE_KEY, today)
    }
}

/** The experimental switches as reducers read them, overridable in tests (iOS `ExperimentalFeatures`). */
class ExperimentalFeatures(val isEnabled: (ExperimentalFeature) -> Boolean) {
    operator fun invoke(feature: ExperimentalFeature): Boolean = isEnabled(feature)

    companion object Key : DependencyKey<ExperimentalFeatures> {
        override val liveValue: ExperimentalFeatures = ExperimentalFeatures { feature -> shared(feature.key).value }
        override val testValue: ExperimentalFeatures = ExperimentalFeatures { false }

        /** Every experiment on, or only [features]. */
        fun enabling(features: Set<ExperimentalFeature> = ExperimentalFeature.entries.toSet()) =
            ExperimentalFeatures { it in features }
    }
}
