package ai.opencode.core.ui

import ai.opencode.core.sharing.SharedKey

/**
 * Settings → Appearance's persisted choices (iOS `Theme.swift`: `SharedKey.appTheme`,
 * `SharedKey.appearanceColorScheme`), same names and defaults.
 *
 * Read in UI with [rememberAppAppearance] (or `shared(ThemeSharedKeys.appTheme).flow`);
 * mutate only from the Settings reducer, and only when the value changes.
 */
object ThemeSharedKeys {
    /** The chosen theme; [AppTheme.OpenCode] (the desktop brand palette) by default on this branch. */
    val appTheme = SharedKey.appStorage("appearance-theme", AppTheme.OpenCode)

    /** System / Light / Dark override; [AppearanceColorScheme.System] by default. */
    val appearanceColorScheme = SharedKey.appStorage("appearance-color-scheme", AppearanceColorScheme.System)
}
