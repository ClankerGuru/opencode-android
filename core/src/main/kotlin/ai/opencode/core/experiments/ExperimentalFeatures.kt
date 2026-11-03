package ai.opencode.core.experiments

import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.experimental
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Settings' half of iOS `OpenCode/Settings/ExperimentalFeatures.swift`, in `:core` beside the
 * registry itself (`ai.opencode.core.sharing.ExperimentalFeature`, `experimental(feature)`,
 * `ExperimentalFeatures`): the groups, Call Style, Model Picker and the launch migrations of
 * removed switches. The typealiases let code import everything experimental from this package.
 */
typealias ExperimentalFeature = ai.opencode.core.sharing.ExperimentalFeature
typealias ExperimentalFeatures = ai.opencode.core.sharing.ExperimentalFeatures

/**
 * One experimental feature's switch (iOS `.experimental(feature)`): `experimental-<raw>`, off by
 * default unless it has graduated.
 */
val ExperimentalFeature.key: SharedKey<Boolean> get() = experimentalKeys.getValue(this)

private val experimentalKeys: Map<ExperimentalFeature, SharedKey<Boolean>> =
    ExperimentalFeature.entries.associateWith { experimental(it) }

/** SF Symbol name (iOS `systemImage`). */
val ExperimentalFeature.systemImage: String get() = symbol

/** Written by Settings' Call Style menu rather than shown as a switch. */
val ExperimentalFeature.isCallStyle: Boolean get() = ExperimentalCallStyle.entries.any { it.feature == this }

/** The sections of Settings › Experimental, in order, all open. */
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

    /** The group's experiments, graduated ones aside. */
    val features: List<ExperimentalFeature>
        get() = ExperimentalFeature.allCases.filter { it.group == this && !it.isGraduated }

    /** The group's switches: every feature but those a style menu writes (Call Style's). */
    val toggles: List<ExperimentalFeature> get() = features.filter { !it.isCallStyle }
}

/**
 * Settings' one Call Style choice, stored as the two call experiments so saved switches carry
 * over. Living Orb is the floating call and the default (`livingOrb` is on until switched off);
 * it replaced the old Floating Call switch. [migrateToLivingOrbDefault] moves earlier installs to
 * it once. Living Orb wins when both are on.
 */
enum class ExperimentalCallStyle {
    standard, floating, tray;

    val id: String get() = name
    val callTray: Boolean get() = this == tray
    val livingOrb: Boolean get() = this == floating

    /** The experiment this style turns on. */
    val feature: ExperimentalFeature?
        get() = when (this) {
            standard -> null
            floating -> ExperimentalFeature.livingOrb
            tray -> ExperimentalFeature.callTray
        }

    val title: String
        get() = when (this) {
            standard -> "Standard"
            floating -> ExperimentalFeature.livingOrb.title
            tray -> "Tray"
        }

    val summary: String
        get() = when (this) {
            standard -> "The built-in call view"
            floating -> ExperimentalFeature.livingOrb.summary
            tray -> ExperimentalFeature.callTray.summary
        }

    companion object {
        /** The old Floating Call switch's saved key. */
        const val LEGACY_FLOATING_KEY = "experimental-floating-call"

        /** iOS `init(callTray:livingOrb:)`. */
        fun of(callTray: Boolean, livingOrb: Boolean): ExperimentalCallStyle =
            if (livingOrb) floating else if (callTray) tray else standard

        /** iOS `defaultStyle`: the style with neither switch set, Living Orb. */
        val defaultStyle: ExperimentalCallStyle
            get() = of(
                callTray = ExperimentalFeature.callTray.defaultEnabled,
                livingOrb = ExperimentalFeature.livingOrb.defaultEnabled,
            )

        /** Marks the one-time move to Living Orb as done. */
        const val LIVING_ORB_MIGRATION_KEY = "call-style-migrated-orb-default"

        /**
         * Living Orb became the default (iOS, Nov 1): once per install, before anything reads a
         * call-style switch (`migrateBeforeLaunch`). A saved switch can't tell a choice from an
         * untouched default, so a Tray in use stays the Tray (a saved Floating Call switch it
         * overrode included) and everyone else takes Living Orb. A choice made after that sticks.
         *
         * Android divergence: iOS's lab `OPENCODE_LAB_EXPERIMENTS_OFF` (UI suites starting Living
         * Orb off) is n/a; Android has no lab experiment overrides.
         */
        fun migrateToLivingOrbDefault(store: SharedStore) {
            val marker = store.reference(SharedKey.appStorage(LIVING_ORB_MIGRATION_KEY, false))
            if (marker.value) return
            marker.set(true)
            val livingOrbKey = experimental(ExperimentalFeature.livingOrb)
            val trayKey = experimental(ExperimentalFeature.callTray)
            store.remove(SharedKey.appStorage(LEGACY_FLOATING_KEY, false))
            val trayInUse = store.contains(trayKey) && store.reference(trayKey).value
            val orbSaved = store.contains(livingOrbKey) && store.reference(livingOrbKey).value
            if (trayInUse && !orbSaved) {
                store.reference(livingOrbKey).set(false)
            } else {
                // Unset, it reads the default: Living Orb.
                store.remove(livingOrbKey)
            }
        }
    }
}

/**
 * Settings' one Model Picker choice: the model chip opens today's menu, a stock sheet with each
 * model's level (`ModelPickerSheet`), One Picker, or the composer grows into the custom picker
 * (WIP). Replaced the New Model Picker and Custom Model Picker switches; [migrateLegacySwitches]
 * carries them over.
 */
@Serializable
enum class ModelPickerStyle(val rawValue: String) {
    @SerialName("today") today("today"),
    @SerialName("sheet") sheet("sheet"),
    @SerialName("one-picker") onePicker("one-picker"),
    @SerialName("grow-from-composer") growFromComposer("grow-from-composer");

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            today -> "Today"
            sheet -> "Sheet"
            onePicker -> "One Picker"
            growFromComposer -> "Grow from Composer"
        }

    val summary: String
        get() = when (this) {
            today -> "The built-in model menu"
            sheet -> "Pick a model in a sheet"
            onePicker -> "Favorites, recents, and bottom search"
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
         * Moves the old New Model Picker and Custom Model Picker switches into the style once,
         * then removes them. A style already chosen stays.
         */
        fun migrateLegacySwitches(store: SharedStore) {
            val sheetKey = SharedKey.appStorage(LEGACY_SHEET_KEY, false)
            val growKey = SharedKey.appStorage(LEGACY_GROW_KEY, false)
            val sheet = store.contains(sheetKey)
            val grow = store.contains(growKey)
            if (!sheet && !grow) return
            if (!store.contains(key)) {
                val style = of(store.reference(sheetKey).value, store.reference(growKey).value)
                if (style != today) store.reference(key).set(style)
            }
            store.remove(sheetKey)
            store.remove(growKey)
        }

        /** The style in use. iOS lab runs may override it; Android uses the saved one. */
        fun active(stored: ModelPickerStyle): ModelPickerStyle = stored

        /**
         * `model-picker=sheet` (or `=grow-from-composer`, `=today`) in an experiments list; the
         * old `new-model-picker` and `custom-model-picker` names still pick Sheet and Grow from
         * Composer, and `one-picker` picks One Picker.
         */
        fun labStyle(experiments: String): ModelPickerStyle? {
            val names = experiments.split(",").map { it.trim() }.toSet()
            names.firstNotNullOfOrNull { name ->
                if (!name.startsWith("model-picker=")) null
                else entries.firstOrNull { it.rawValue == name.removePrefix("model-picker=") }
            }?.let { return it }
            if ("one-picker" in names) return onePicker
            if ("custom-model-picker" in names) return growFromComposer
            if ("new-model-picker" in names) return sheet
            return null
        }

        /** Settings › Experimental › Model Picker. Today by default. Lazy: the enum serializer isn't ready during companion init. */
        val key: SharedKey<ModelPickerStyle> by lazy { SharedKey.appStorage(STORAGE_KEY, today) }
    }
}
