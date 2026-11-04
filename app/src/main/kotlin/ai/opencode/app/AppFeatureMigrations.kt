package ai.opencode.app

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.experiments.migrateCallStyles
import ai.opencode.core.experiments.migrateModelPicker
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.InstallHistory
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.adoptNewExperimentsIntoAll
import ai.opencode.core.sharing.installHistory
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewFeature
import ai.opencode.feature.settings.whatsnew.all
import ai.opencode.server.OpenCodeServer

/*
 * Port of iOS `AppFeature+Migrations.swift`: preferences saved by earlier builds, moved once as
 * the app starts (`Action.Task`). The GPT-Live engine move and the Console sign-in removal are
 * n/a: no Android build ever saved a Realtime/Grok engine or a Console sign-in. Android never
 * wrote iOS's `living-orb-presets.json`, so only the style key goes.
 */

/**
 * iOS `AppFeature.migrateBeforeLaunch()`: moves that must land before the first state reads its
 * settings. [OpenCodeApplication] runs them before any window makes its store, whose state (a
 * restored session included) reads shared switches as it's made.
 */
internal fun migrateBeforeLaunch() {
    ExperimentalFeature.migrateCallStyles(dependency(SharedStore))
}

/** Runs every launch migration, in iOS's order. */
internal fun migrateSettingsIfNeeded(savedServers: List<OpenCodeServer>) {
    val store = dependency(SharedStore)
    recordInstallHistory(store, savedServers)
    ExperimentalFeature.migrateModelPicker(store)
    ExperimentalFeature.removeRetiredSwitches(store)
    ExperimentalFeature.adoptNewExperimentsIntoAll()
    removeRetiredOrbStyles(store)
}

/** iOS `AppFeature.retiredOrbStyleKey`: the voice orb is Wisp's alone, so the old style pick goes. */
internal const val RETIRED_ORB_STYLE_KEY = "living-orb-style"

private fun removeRetiredOrbStyles(store: SharedStore) {
    store.remove(SharedKey.appStorage(RETIRED_ORB_STYLE_KEY, ""))
}

/**
 * Records once whether this is a new install (iOS t485). A new one starts with What’s New read:
 * everything listed came before the person did. Anything an earlier build saved (a server, a seen
 * entry, a one-time migration's marker) makes it an upgrade, which keeps what it hasn't read and
 * counts as having paired. Removing servers never resets it.
 */
private fun recordInstallHistory(store: SharedStore, savedServers: List<OpenCodeServer>) {
    val history = store.reference(SharedKeys.installHistory)
    if (history.value != InstallHistory.unrecorded) return
    val seenIDs = store.reference(WhatsNewFeature.seenIDsKey)
    val upgrading = savedServers.isNotEmpty() || seenIDs.value.isNotEmpty() ||
        InstallHistory.legacyMarkers.any { store.contains(SharedKey.appStorage(it, false)) }
    if (upgrading) {
        history.set(InstallHistory.paired)
        return
    }
    val all = WhatsNewEntry.all
    seenIDs.set(all.map { it.id }.toSet())
    history.set(InstallHistory.fresh)
    AppLog.app.info { "fresh install marked What’s New seen entries=${all.size}" }
}
