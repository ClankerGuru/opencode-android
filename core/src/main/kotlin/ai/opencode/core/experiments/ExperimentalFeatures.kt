package ai.opencode.core.experiments

import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.experimental

/**
 * Settings' half of iOS `OpenCode/Settings/ExperimentalFeatures.swift`, in `:core` beside the
 * registry itself (`ai.opencode.core.sharing.ExperimentalFeature`, `experimental(feature)`,
 * `ExperimentalFeatures`): the groups and the launch migrations of removed switches. The
 * typealiases let code import everything experimental from this package.
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
