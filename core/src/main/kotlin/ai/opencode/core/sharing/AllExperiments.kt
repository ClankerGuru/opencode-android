package ai.opencode.core.sharing

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog

/**
 * Port of iOS `OpenCode/Settings/Experimental/AllExperiments.swift`. All Experiments (p217): one
 * switch that turns every experiment on, new ones included, except those that ask for a
 * permission or share data ([ExperimentalFeature.joinsAll]). It writes each experiment's own
 * switch, so everything that reads `shared(experimental(…))` sees it without change.
 *
 * Switching it on saves each switch ([allExperimentsSaved]) and turns on all but the ones skipped;
 * switching one off while it's on records a skip ([allExperimentsSkips]), kept for next time.
 * Switching it off puts back what was saved. An experiment that arrives while it's on has nothing
 * saved yet: launch saves it and turns it on ([adoptNewExperimentsIntoAll]).
 */
object AllExperiments {
    /** Settings › Experimental › All Experiments (iOS `.allExperiments`). */
    val key: SharedKey<Boolean> = SharedKey.appStorage("experimental-all", false)

    /** One experiment whose switch All Experiments changed, and what it's now. */
    data class Change(val feature: ExperimentalFeature, val on: Boolean)
}

/**
 * Switched off by hand while All Experiments was on: it stays off when All Experiments switches
 * everything on (iOS `.allExperimentsSkips(feature)`).
 */
fun allExperimentsSkips(feature: ExperimentalFeature): SharedKey<Boolean> =
    SharedKey.appStorage("experimental-all-skip-${feature.rawValue}", false)

/**
 * The switch as it was before All Experiments turned on, put back when it turns off; null while
 * it's off (iOS `.allExperimentsSaved(feature)`).
 */
fun allExperimentsSaved(feature: ExperimentalFeature): SharedKey<Boolean?> =
    SharedKey.appStorage<Boolean?>("experimental-all-saved-${feature.rawValue}", null)

/** The experiments All Experiments switches, in Settings order (iOS `joiningAll`). */
val ExperimentalFeature.Companion.joiningAll: List<ExperimentalFeature>
    get() {
        val result = mutableListOf<ExperimentalFeature>()
        for (feature in ExperimentalFeature.allCases) {
            if (feature.joinsAll) result.add(feature)
        }
        return result
    }

/** Saves each switch, then turns on every experiment not skipped. Returns the ones whose switch changed. */
fun ExperimentalFeature.Companion.switchAllOn(): List<AllExperiments.Change> {
    val changed = mutableListOf<AllExperiments.Change>()
    for (feature in joiningAll) {
        val enabled = shared(experimental(feature))
        val skipped = shared(allExperimentsSkips(feature)).value
        shared(allExperimentsSaved(feature)).set(enabled.value)
        val next = !skipped
        if (enabled.value != next) {
            enabled.set(next)
            changed.add(AllExperiments.Change(feature, next))
        }
    }
    return changed
}

/** Puts back each saved switch and forgets it, keeping the skips. Returns the ones whose switch changed. */
fun ExperimentalFeature.Companion.restoreFromAll(): List<AllExperiments.Change> {
    val store = dependency(SharedStore)
    val changed = mutableListOf<AllExperiments.Change>()
    for (feature in joiningAll) {
        val enabled = shared(experimental(feature))
        val previous = shared(allExperimentsSaved(feature)).value ?: continue
        store.remove(allExperimentsSaved(feature))
        if (enabled.value != previous) {
            enabled.set(previous)
            changed.add(AllExperiments.Change(feature, previous))
        }
    }
    return changed
}

/**
 * At launch with All Experiments on, an experiment with nothing saved arrived since: save its
 * switch and turn it on unless it was skipped.
 */
fun ExperimentalFeature.Companion.adoptNewExperimentsIntoAll() {
    if (!shared(AllExperiments.key).value) return
    for (feature in joiningAll) {
        val saved = shared(allExperimentsSaved(feature))
        if (saved.value != null) continue
        val enabled = shared(experimental(feature))
        saved.set(enabled.value)
        val next = !shared(allExperimentsSkips(feature)).value
        if (enabled.value != next) {
            enabled.set(next)
            AppLog.app.info("All Experiments turned on a new experiment feature=${feature.rawValue}")
        }
    }
}
