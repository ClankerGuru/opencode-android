package ai.opencode.devtools.tuning

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.tuning.TuneValue
import ai.opencode.core.tuning.TuningContext
import ai.opencode.core.tuning.TuningSharedKeys
import ai.opencode.core.tuning.TuningStore
import kotlin.time.Duration.Companion.seconds

/**
 * Android counterpart of iOS `TuningFeature` (`OpenCode/Tuning/TuningFeature.swift`): the floating
 * Tuning Panel's state and actions. The panel edits [TuningStore] live; the Mac tune page link
 * (`TuningRemote`) and the playground controls are not ported (see docs/parity/devtools.md).
 */
class TuningFeature : Reducer<TuningFeature.State, TuningFeature.Action> {
    data class State(
        /** On screen at all: the panel or its pill. */
        val isPanelShown: Boolean = false,
        /** Every group, not just this screen's. */
        val showsAll: Boolean = false,
        /** Folded into the pill. Kept across launches. */
        val isCollapsed: Boolean = false,
        val didCopy: Boolean = false,
        val lastCopied: String? = null,
        /** Where the panel sits, as fractions of the free area (x 0 = left edge, 1 = right). */
        val placementX: Float = 1f,
        val placementY: Float = 0.12f,
        /** Groups folded to their header. Kept across launches. */
        val foldedGroups: Set<String> = emptySet(),
        /** The Developer screen, opened over the app from the panel's menu. */
        val showsDeveloperTools: Boolean = false,
    )

    sealed interface Action {
        data object Task : Action
        data object Stopped : Action
        /** The summon gesture (three-finger double tap / four-finger hold) or Settings' button. */
        data object Summoned : Action
        data object CloseTapped : Action
        data object CollapseTapped : Action
        data object ExpandTapped : Action
        data object ShowsAllToggled : Action
        data class GroupHidden(val group: String, val hidden: Boolean) : Action
        data object TurnOffTapped : Action
        data class ValueChanged(val key: String, val value: TuneValue?) : Action
        data class PresetSelected(val key: String, val index: Int) : Action
        data class RandomizeTapped(val group: String) : Action
        data class UndoRollTapped(val group: String) : Action
        data class ResetTapped(val group: String?) : Action
        data class CopyValuesTapped(val group: String?) : Action
        data class CopyKotlinTapped(val group: String?) : Action
        data object CopiedShown : Action
        /** The header was dragged and released here (fractions of the free area). */
        data class PlacementChanged(val x: Float, val y: Float) : Action
        data class GroupFolded(val group: String, val folded: Boolean) : Action
        data object DeveloperToolsTapped : Action
        data object DeveloperToolsClosed : Action
    }

    private enum class CancelID { Copied }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val tuning = dependency(TuningStore)
        return when (action) {
            Action.Task -> Reduced(
                state.copy(
                    isCollapsed = shared(TuningPanelKeys.collapsed).value,
                    placementX = shared(TuningPanelKeys.placementX).value,
                    placementY = shared(TuningPanelKeys.placementY).value,
                    foldedGroups = shared(TuningPanelKeys.foldedGroups).value.split(',').filter { it.isNotEmpty() }.toSet(),
                ),
            )
            Action.Stopped -> Reduced(state.copy(isPanelShown = false))
            Action.Summoned -> {
                val shown = !state.isPanelShown
                if (shown) dependency(HapticsClient).play(Haptic.confirm)
                Reduced(state.copy(isPanelShown = shown))
            }
            Action.CloseTapped -> Reduced(state.copy(isPanelShown = false))
            Action.CollapseTapped -> {
                shared(TuningPanelKeys.collapsed).set(true)
                Reduced(state.copy(isCollapsed = true))
            }
            Action.ExpandTapped -> {
                shared(TuningPanelKeys.collapsed).set(false)
                Reduced(state.copy(isCollapsed = false))
            }
            Action.ShowsAllToggled -> Reduced(state.copy(showsAll = !state.showsAll))
            is Action.GroupHidden -> {
                tuning.setHidden(action.group, action.hidden)
                Reduced(state)
            }
            Action.TurnOffTapped -> {
                shared(TuningSharedKeys.tuningEnabled).set(false)
                tuning.setEnabled(false)
                Reduced(state.copy(isPanelShown = false))
            }
            is Action.ValueChanged -> {
                tuning.set(action.key, action.value)
                Reduced(state)
            }
            is Action.PresetSelected -> {
                tuning.applyPreset(action.key, action.index)
                Reduced(state)
            }
            is Action.RandomizeTapped -> {
                tuning.randomize(action.group)
                Reduced(state)
            }
            is Action.UndoRollTapped -> {
                tuning.undoRoll(action.group)
                Reduced(state)
            }
            is Action.ResetTapped -> {
                tuning.resetAll(action.group)
                Reduced(state)
            }
            is Action.CopyValuesTapped -> copySnippet(tuning.valuesSnippet(action.group), state)
            is Action.CopyKotlinTapped -> copySnippet(tuning.kotlinSnippet(action.group), state)
            Action.CopiedShown -> Reduced(state.copy(didCopy = false))
            is Action.PlacementChanged -> {
                val x = action.x.coerceIn(0f, 1f)
                val y = action.y.coerceIn(0f, 1f)
                if (x != state.placementX) shared(TuningPanelKeys.placementX).set(x)
                if (y != state.placementY) shared(TuningPanelKeys.placementY).set(y)
                Reduced(state.copy(placementX = x, placementY = y))
            }
            Action.DeveloperToolsTapped -> Reduced(state.copy(showsDeveloperTools = true, isPanelShown = false))
            Action.DeveloperToolsClosed -> Reduced(state.copy(showsDeveloperTools = false))
            is Action.GroupFolded -> {
                val folded = if (action.folded) state.foldedGroups + action.group else state.foldedGroups - action.group
                shared(TuningPanelKeys.foldedGroups).set(folded.sorted().joinToString(","))
                Reduced(state.copy(foldedGroups = folded))
            }
        }
    }

    private fun copySnippet(snippet: String, state: State): Reduced<State, Action> {
        if (snippet.isEmpty()) return Reduced(state)
        dependency(HapticsClient).play(Haptic.tap)
        val pasteboard = dependency(PasteboardClient)
        val clock = dependency(Clock)
        return Reduced(
            state.copy(didCopy = true, lastCopied = snippet),
            Effect.run<Action> { send ->
                pasteboard.setString(snippet)
                clock.sleep(1.5.seconds)
                send(Action.CopiedShown)
            }.cancellable(CancelID.Copied, cancelInFlight = true),
        )
    }
}

/** iOS tuning panel keys (`tuning-panel-collapsed`, folded groups and placement). */
object TuningPanelKeys {
    val collapsed: SharedKey<Boolean> = SharedKey.appStorage("tuning-panel-collapsed", false)
    val foldedGroups: SharedKey<String> = SharedKey.appStorage("tuning-folded-groups", "")
    /** Where the panel sits: fractions of the free area, as iOS keeps its placement across launches. */
    val placementX: SharedKey<Float> = SharedKey.appStorage("tuning-panel-x", 1f)
    val placementY: SharedKey<Float> = SharedKey.appStorage("tuning-panel-y", 0.12f)
}

/** iOS `TuningPanelGroups.shown`: this screen's groups that have tunables and aren't settled; with All, every unsettled group, this screen's first. */
object TuningPanelGroups {
    fun shown(registered: List<String>, context: TuningContext, showsAll: Boolean, hidden: Set<String>): List<String> {
        val available = registered.filter { it !in hidden }
        val here = context.groups.filter { it in available }
        if (!showsAll) return here
        return here + available.filter { it !in here }
    }
}
