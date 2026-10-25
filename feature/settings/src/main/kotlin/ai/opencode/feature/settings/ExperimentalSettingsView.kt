package ai.opencode.feature.settings

import ai.opencode.core.experiments.key
import ai.opencode.core.experiments.group
import ai.opencode.core.experiments.systemImage
import ai.opencode.core.experiments.isCallStyle
import ai.opencode.core.experiments.ExperimentalCallStyle
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.ExperimentalGroup
import ai.opencode.core.experiments.ModelPickerStyle
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.devtools.DevTools
import ai.opencode.devtools.DevToolsView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/**
 * Port of iOS `Settings/ExperimentalSettingsView.swift`: Settings → Advanced, the experimental
 * switches in open groups (Composer, Calls, Transcript, Drawer) and Developer at the bottom.
 * Opened for one experiment (What’s New), it scrolls to that row and briefly highlights it
 * (1.2 s, then a 0.6 s ease-out).
 *
 * Partial: Living Orb's **Orb Style** menu waits for `:feature:voice`'s `LivingOrbStyle`.
 */
@Composable
fun ExperimentalSettingsView(store: SettingsStore, focus: ExperimentalFeature?) {
    val developerMode by remember { shared(SharedKeys.developerMode).flow }.collectAsState()
    val modelPickerStored by remember { shared(ModelPickerStyle.key).flow }.collectAsState()
    val callTray by remember { shared(ExperimentalFeature.callTray.key).flow }.collectAsState()
    val floatingCall by remember { shared(ExperimentalFeature.floatingCall.key).flow }.collectAsState()
    // Every switch, so headers count what's on and rows redraw when a value changes.
    val enabled = ExperimentalFeature.entries.associateWith { feature ->
        remember(feature) { shared(feature.key).flow }.collectAsState().value
    }
    val send = { action: HomeSettingsFeature.Action -> store.send(SettingsFeature.Action.Settings(action)) }
    val modelPicker = ModelPickerStyle.active(modelPickerStored)
    val callStyle = ExperimentalCallStyle.of(callTray = callTray, floatingCall = floatingCall)
    val listState = rememberLazyListState()
    var highlighted by remember { mutableStateOf<ExperimentalFeature?>(null) }
    val reduceMotion = rememberReduceMotion()

    // Scroll to the focused row (each group's section is one list item, in order) and tint it.
    LaunchedEffect(focus) {
        val target = focus?.settingsRowID ?: return@LaunchedEffect
        listState.scrollToItem(ExperimentalGroup.entries.indexOf(target.group))
        highlighted = target
        delay(1200)
        highlighted = null
    }

    @Composable
    fun ColumnScope.toggles(group: ExperimentalGroup, leadingDivider: Boolean) {
        group.toggles.forEachIndexed { index, feature ->
            if (leadingDivider || index > 0) SettingsDivider(inset = true)
            ExperimentalFeatureToggle(
                feature = feature,
                enabled = enabled.getValue(feature),
                highlighted = highlighted == feature,
                reduceMotion = reduceMotion,
                onChange = { send(HomeSettingsFeature.Action.ExperimentalFeatureChanged(feature, it)) },
            )
        }
    }

    fun header(group: ExperimentalGroup, styled: Int = 0): String {
        val on = styled + group.toggles.count { enabled.getValue(it) }
        return if (on == 0) group.title else "${group.title} · $on on"
    }

    SettingsPageScaffold(title = "Advanced", store = store, listState = listState) {
        settingsSection(
            key = ExperimentalGroup.composer,
            header = header(ExperimentalGroup.composer, if (modelPicker != ModelPickerStyle.today) 1 else 0),
        ) {
            SettingsMenuRow(
                title = "Model Picker",
                value = modelPicker.title,
                selection = modelPicker,
                options = ModelPickerStyle.entries.map { SettingsMenuOption(it, it.title) },
                onSelect = { send(HomeSettingsFeature.Action.ModelPickerStyleChanged(it)) },
                caption = modelPicker.summary,
                symbol = "cpu",
                tint = ExperimentalGroup.composer.tint,
            )
            toggles(ExperimentalGroup.composer, leadingDivider = true)
        }
        settingsSection(
            key = ExperimentalGroup.calls,
            header = header(ExperimentalGroup.calls, if (callStyle != ExperimentalCallStyle.standard) 1 else 0),
        ) {
            val highlight by animateColorAsState(
                if (highlighted == ExperimentalFeature.callTray) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
                tween(if (reduceMotion || highlighted == ExperimentalFeature.callTray) 0 else 600),
                label = "call-style-highlight",
            )
            SettingsMenuRow(
                title = "Call Style",
                value = callStyle.title,
                selection = callStyle,
                options = ExperimentalCallStyle.entries.map { SettingsMenuOption(it, it.title) },
                onSelect = { style ->
                    send(HomeSettingsFeature.Action.ExperimentalFeatureChanged(ExperimentalFeature.callTray, style.callTray))
                    send(HomeSettingsFeature.Action.ExperimentalFeatureChanged(ExperimentalFeature.floatingCall, style.floatingCall))
                },
                caption = callStyle.summary,
                symbol = "phone.fill",
                tint = ExperimentalGroup.calls.tint,
                modifier = Modifier.background(highlight),
            )
            toggles(ExperimentalGroup.calls, leadingDivider = true)
        }
        settingsSection(key = ExperimentalGroup.transcript, header = header(ExperimentalGroup.transcript)) {
            toggles(ExperimentalGroup.transcript, leadingDivider = false)
        }
        settingsSection(
            key = ExperimentalGroup.drawer,
            header = header(ExperimentalGroup.drawer),
            footer = footerText("Unfinished features that may change or go away."),
        ) {
            toggles(ExperimentalGroup.drawer, leadingDivider = false)
        }
        settingsSection(
            key = "developer",
            footer = footerText("Diagnostics, live motion tuning, and developer sandboxes."),
        ) {
            SettingsRow("Developer", "hammer.fill", SettingsTint.Teal, value = if (developerMode) "On" else "Off") {
                store.send(SettingsFeature.Action.PagePushed(SettingsPage.Developer))
            }
        }
    }
}

/** One experiment's switch: its tinted symbol, title, and a few words. */
@Composable
fun ExperimentalFeatureToggle(
    feature: ExperimentalFeature,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    highlighted: Boolean = false,
    reduceMotion: Boolean = false,
) {
    val highlight by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
        // Tinted at once, then fades out over 0.6 s (iOS `.easeOut(duration: 0.6)`).
        tween(if (reduceMotion || highlighted) 0 else 600),
        label = "experiment-highlight",
    )
    SettingsToggleRow(
        title = feature.title,
        checked = enabled,
        onCheckedChange = onChange,
        symbol = feature.systemImage,
        tint = feature.group.tint,
        summary = feature.summary,
        modifier = Modifier.background(highlight),
    )
}

/** The row an experiment lives on: Call Style's for the styles it writes. */
val ExperimentalFeature.settingsRowID: ExperimentalFeature
    get() = if (isCallStyle) ExperimentalFeature.callTray else this

val ExperimentalGroup.tint: SettingsTint
    get() = when (this) {
        ExperimentalGroup.composer -> SettingsTint.Blue
        ExperimentalGroup.calls -> SettingsTint.Green
        ExperimentalGroup.transcript -> SettingsTint.Orange
        ExperimentalGroup.drawer -> SettingsTint.Purple
    }

/**
 * Settings › Advanced › Developer (iOS `Diagnostics/DiagnosticsView.swift`): `:devtools`'
 * Developer screen (Developer Mode, Record Diagnostics, Demo Call Audio, Troubleshooting,
 * Tuning, Android's tools), with Settings' Playgrounds under Tuning as on iOS. Its sub-pages
 * go back inside it; its main page's back pops Settings' stack. The overlay shares the store.
 */
@Composable
fun DeveloperSettingsView(store: SettingsStore) {
    DevToolsView(
        DevTools.developerStore,
        Modifier.fillMaxSize(),
        onClose = { store.send(SettingsFeature.Action.BackTapped) },
        onOpenPlaygrounds = { store.send(SettingsFeature.Action.PagePushed(SettingsPage.Playgrounds)) },
    )
}

/**
 * Port of iOS `PlaygroundsSettingsView` (`Settings/SettingsView.swift`). The Transcript, Session
 * List and Composer playgrounds are iOS Tuning tools (`:devtools` on Android); Onboarding ports here.
 */
@Composable
fun PlaygroundsSettingsView(store: SettingsStore) {
    SettingsPageScaffold(title = "Playgrounds", store = store) {
        settingsSection(key = "playgrounds", footer = footerText("Try visual changes with built-in examples and no server.")) {
            SettingsRow("Onboarding", "dot.radiowaves.left.and.right", SettingsTint.Indigo, showsChevron = false) {
                store.send(SettingsFeature.Action.Settings(HomeSettingsFeature.Action.OnboardingPlaygroundOpened))
            }
        }
    }
}
