package ai.opencode.core.experiments

import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.experimental

/**
 * Settings' half of iOS `OpenCode/Settings/ExperimentalFeatures.swift`, in `:core` beside the
 * registry itself (`ai.opencode.core.sharing.ExperimentalFeature`, `experimental(feature)`,
 * `ExperimentalFeatures`): the groups, Call Style and the launch migrations of removed
 * switches. The typealiases let code import everything experimental from this package.
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

/** The removed Model Picker menu's saved style (`today`, `sheet`, `one-picker`, `grow-from-composer`). */
const val LEGACY_MODEL_PICKER_KEY = "experimental-model-picker"

/**
 * The switches the Model Picker menu replaced: New Model Picker (Sheet) and Custom Model Picker
 * (Grow from Composer).
 */
val legacyModelPickerSwitchKeys = listOf("experimental-new-model-picker", "experimental-custom-model-picker")

/**
 * iOS `ExperimentalFeature.migrateModelPicker(in:)`: moves a saved Model Picker style, or the
 * older switches before it, to One Picker once, then removes them. Sheet and Grow from Composer
 * are gone (iOS 0fbb99f8), and any of them but Today means One Picker. (Fully qualified: a
 * typealias can't reach the enum's companion.)
 */
fun ai.opencode.core.sharing.ExperimentalFeature.Companion.migrateModelPicker(store: SharedStore) {
    val styleKey = SharedKey.appStorage<String?>(LEGACY_MODEL_PICKER_KEY, null)
    val switchKeys = mutableListOf<SharedKey<Boolean>>()
    for (name in legacyModelPickerSwitchKeys) {
        switchKeys.add(SharedKey.appStorage(name, false))
    }
    var anySaved = store.contains(styleKey)
    for (key in switchKeys) {
        if (store.contains(key)) anySaved = true
    }
    if (!anySaved) return

    val style = if (store.contains(styleKey)) store.reference(styleKey).value else null
    var picksOnePicker = style != null && style != "today"
    for (key in switchKeys) {
        if (store.contains(key) && store.reference(key).value) picksOnePicker = true
    }
    if (picksOnePicker) store.reference(experimental(ExperimentalFeature.onePicker)).set(true)

    store.remove(styleKey)
    for (key in switchKeys) store.remove(key)
}
