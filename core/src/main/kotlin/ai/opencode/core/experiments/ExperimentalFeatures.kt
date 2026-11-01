package ai.opencode.core.experiments

import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.experimental
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Settings' half of iOS `OpenCode/Settings/ExperimentalFeatures.swift`, in `:core` beside the
 * enum itself (`ai.opencode.core.sharing.ExperimentalFeature`, `experimental(feature)`,
 * `ExperimentalFeatures`): each case's Settings group, the groups, Call Style and Model Picker.
 * The typealiases let code import everything experimental from this package.
 */
typealias ExperimentalFeature = ai.opencode.core.sharing.ExperimentalFeature
typealias ExperimentalFeatures = ai.opencode.core.sharing.ExperimentalFeatures

/** One experimental feature's switch (iOS `.experimental(feature)`): `experimental-<raw>`, off by default. */
val ExperimentalFeature.key: SharedKey<Boolean> get() = experimentalKeys.getValue(this)

private val experimentalKeys: Map<ExperimentalFeature, SharedKey<Boolean>> =
    ExperimentalFeature.entries.associateWith { experimental(it) }

/** SF Symbol name (iOS `systemImage`). */
val ExperimentalFeature.systemImage: String get() = symbol

/** Settings section the switch sits in. */
val ExperimentalFeature.group: ExperimentalGroup
    get() = when (this) {
        ExperimentalFeature.composerAutocomplete, ExperimentalFeature.plusMenuSheet,
        ExperimentalFeature.agentSelection, ExperimentalFeature.hideModels -> ExperimentalGroup.composer
        ExperimentalFeature.callTray, ExperimentalFeature.floatingCall, ExperimentalFeature.livingOrb -> ExperimentalGroup.calls
        ExperimentalFeature.newMessagesDivider, ExperimentalFeature.sinceYouLeft,
        ExperimentalFeature.imageLinkPreviews -> ExperimentalGroup.transcript
        ExperimentalFeature.projectIcons, ExperimentalFeature.sessionSwitcher,
        ExperimentalFeature.serverTitleSwitch, ExperimentalFeature.serverDetails -> ExperimentalGroup.drawer
        ExperimentalFeature.inAppBrowser -> ExperimentalGroup.transcript
        ExperimentalFeature.serverVoice, ExperimentalFeature.backgroundCallIntent,
        ExperimentalFeature.voiceSessionDelegator -> ExperimentalGroup.calls
    }

/** Written by Settings' Call Style menu rather than shown as a switch. */
val ExperimentalFeature.isCallStyle: Boolean get() = ExperimentalCallStyle.entries.any { it.feature == this }

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

        /** Settings › Advanced › Model Picker. Today by default. Lazy: the enum serializer isn't ready during companion init. */
        val key: SharedKey<ModelPickerStyle> by lazy { SharedKey.appStorage(STORAGE_KEY, today) }
    }
}
