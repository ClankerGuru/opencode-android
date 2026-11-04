package ai.opencode.core.experiments

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.InMemorySharedBytes
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.experimental
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `ExperimentalFeatureTests`. `theSessionsPageDividerSwitchKeepsTheSavedChoice`
 * drives `HomeSettingsFeature`, so it's in `:feature:settings`' `ExperimentalFeatureTests`, with
 * the `settingsRowID` line of `callStyleMapsOntoTheTwoCallExperiments`.
 */
class ExperimentalFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    /** iOS `withDependencies { $0.defaultAppStorage = fresh }`: a fresh test container's storage. */
    private fun <R> withFreshStorage(block: () -> R): R = DependencyValues.withValues(DependencyValues.testing(), block)

    /**
     * iOS `defaults.set(_:forKey:)` on a fresh suite: a raw saved value, written even when it
     * equals the key's default (a nullable key, so `false` is stored rather than skipped).
     */
    private fun SharedStore.save(id: String, value: Boolean) = reference(SharedKey.appStorage<Boolean?>(id, null)).set(value)

    /** iOS `defaults.object(forKey:) != nil`. */
    private fun SharedStore.has(id: String) = contains(SharedKey.appStorage<Boolean?>(id, null))

    /** iOS `defaults.bool(forKey:)`. */
    private fun SharedStore.bool(id: String) = reference(SharedKey.appStorage(id, false)).value

    @Test
    fun experimentsAreOffByDefaultAndPersistUnderAStableKey() {
        withFreshStorage {
            assertFalse(ExperimentalFeatures.liveValue(ExperimentalFeature.inAppBrowser))
            val inAppBrowser = shared(experimental(ExperimentalFeature.inAppBrowser))
            assertFalse(inAppBrowser.value)
            inAppBrowser.set(true)
            assertTrue(ExperimentalFeatures.liveValue(ExperimentalFeature.inAppBrowser))
            assertEquals("The key is the phone's saved choice: never rename it", "experimental-in-app-browser", inAppBrowser.key.id)
            assertTrue(dependency(SharedStore).has("experimental-in-app-browser"))
        }
        assertFalse(ExperimentalFeatures.testValue(ExperimentalFeature.inAppBrowser))
        assertTrue(ExperimentalFeatures.enabling(setOf(ExperimentalFeature.inAppBrowser))(ExperimentalFeature.inAppBrowser))
    }

    /** The New Messages Divider is on for everyone who never touched its switch; someone who switched it off keeps it off. */
    @Test
    fun theNewMessagesDividerIsOnUntilSwitchedOff() {
        withFreshStorage {
            assertTrue(ExperimentalFeatures.liveValue(ExperimentalFeature.newMessagesDivider))
            assertTrue(shared(experimental(ExperimentalFeature.newMessagesDivider)).value)
            assertTrue(ExperimentalFeatures.liveValue(ExperimentalFeature.livingOrb))
            val onByDefault = setOf(ExperimentalFeature.newMessagesDivider, ExperimentalFeature.livingOrb)
            for (feature in ExperimentalFeature.allCases) {
                if (feature in onByDefault) continue
                assertFalse("$feature stays off", ExperimentalFeatures.liveValue(feature))
            }
        }
        withFreshStorage {
            dependency(SharedStore).save("experimental-new-messages-divider", false)
            assertFalse(ExperimentalFeatures.liveValue(ExperimentalFeature.newMessagesDivider))
        }
        assertFalse("tests opt in", ExperimentalFeatures.testValue(ExperimentalFeature.newMessagesDivider))
    }

    /** The divider moved to Settings › Sessions, Welcome Preview to Developer › Playgrounds; neither is in the pane. */
    @Test
    fun theDividerAndWelcomePreviewLeftExperimental() {
        assertEquals(
            listOf(ExperimentalFeature.newMessagesDivider, ExperimentalFeature.welcomePreview),
            ExperimentalFeature.allCases.filter { it.isGraduated },
        )
        assertFalse(ExperimentalGroup.entries.flatMap { it.features }.contains(ExperimentalFeature.newMessagesDivider))
        assertFalse(ExperimentalGroup.entries.flatMap { it.features }.contains(ExperimentalFeature.welcomePreview))
    }

    @Test
    fun everyExperimentHasACopyForSettings() {
        for (feature in ExperimentalFeature.allCases) {
            assertTrue(feature.title.isNotEmpty())
            assertTrue(feature.summary.isNotEmpty())
            assertFalse("One line, no trailing period", feature.summary.endsWith("."))
            assertTrue("A few words: ${feature.summary}", feature.summary.split(" ").filter { it.isNotEmpty() }.size <= 6)
        }
        assertEquals("In-App Browser", ExperimentalFeature.inAppBrowser.title)
    }

    @Test
    fun everyExperimentSitsInOneGroupInAStableOrder() {
        assertEquals(listOf(ExperimentalGroup.composer, ExperimentalGroup.calls, ExperimentalGroup.transcript, ExperimentalGroup.drawer), ExperimentalGroup.entries)
        assertEquals(
            ExperimentalFeature.allCases.filter { !it.isGraduated }.toSet(),
            ExperimentalGroup.entries.flatMap { it.features }.toSet(),
        )
        assertEquals(
            listOf(ExperimentalFeature.onePicker, ExperimentalFeature.agentSelection, ExperimentalFeature.hideModels),
            ExperimentalGroup.composer.features,
        )
        assertEquals(
            listOf(
                ExperimentalFeature.callTray, ExperimentalFeature.livingOrb, ExperimentalFeature.serverVoice,
                ExperimentalFeature.backgroundCallIntent, ExperimentalFeature.voiceSessionDelegator,
                ExperimentalFeature.shareScreenWithAgents, ExperimentalFeature.openLinksFromCalls, ExperimentalFeature.audioInCalls,
            ),
            ExperimentalGroup.calls.features,
        )
        assertEquals(
            "Call Style writes the other two",
            listOf(
                ExperimentalFeature.serverVoice, ExperimentalFeature.backgroundCallIntent, ExperimentalFeature.voiceSessionDelegator,
                ExperimentalFeature.shareScreenWithAgents, ExperimentalFeature.openLinksFromCalls, ExperimentalFeature.audioInCalls,
            ),
            ExperimentalGroup.calls.toggles,
        )
        assertEquals(listOf(ExperimentalFeature.imageLinkPreviews, ExperimentalFeature.inAppBrowser), ExperimentalGroup.transcript.features)
        assertEquals(
            listOf(
                ExperimentalFeature.inboxSidebar, ExperimentalFeature.serverDetails,
                ExperimentalFeature.sessionLiveActivity, ExperimentalFeature.soundEffects,
            ),
            ExperimentalGroup.drawer.features,
        )
        assertEquals(listOf("Composer", "Calls", "Transcript", "Drawer"), ExperimentalGroup.entries.map { it.title })
    }

    /** Removed experiments and ones now always on (p217) leave no switch behind, whatever was saved. */
    @Test
    fun removedExperimentsLeaveNoSwitchBehind() {
        val retired = listOf(
            "session-switcher", "server-title-switch",
            "composer-autocomplete", "server-flow", "plus-menu-sheet", "project-icons",
        )
        withFreshStorage {
            val defaults = dependency(SharedStore)
            for ((index, name) in retired.withIndex()) {
                defaults.save("experimental-$name", index % 2 == 0)
            }
            defaults.save("experimental-in-app-browser", true)
            for (name in retired) {
                assertNull(name, ExperimentalFeature.fromRawValue(name))
            }
            ExperimentalFeature.removeRetiredSwitches(defaults)
            for (name in retired) {
                assertFalse(name, defaults.has("experimental-$name"))
            }
            assertTrue("Live experiments keep their switch", defaults.bool("experimental-in-app-browser"))
        }
    }

    @Test
    fun aSavedSheetOrGrowBecomesOnePickerOnce() {
        fun migrated(setup: (SharedStore) -> Unit): Pair<Boolean, Boolean> = withFreshStorage {
            val defaults = dependency(SharedStore)
            setup(defaults)
            ExperimentalFeature.migrateModelPicker(defaults)
            val keys = listOf(LEGACY_MODEL_PICKER_KEY) + legacyModelPickerSwitchKeys
            var leftover = false
            for (key in keys) {
                if (defaults.contains(SharedKey.appStorage<String?>(key, null))) leftover = true
            }
            defaults.bool("experimental-one-picker") to leftover
        }
        fun SharedStore.saveStyle(style: String) = reference(SharedKey.appStorage<String?>(LEGACY_MODEL_PICKER_KEY, null)).set(style)

        assertEquals(false to false, migrated { })
        assertEquals(false to false, migrated { it.saveStyle("today") })
        for (style in listOf("sheet", "one-picker", "grow-from-composer")) {
            assertEquals(style, true to false, migrated { it.saveStyle(style) })
        }
        for (key in legacyModelPickerSwitchKeys) {
            assertEquals(key, true to false, migrated { it.save(key, true) })
            assertEquals(key, false to false, migrated { it.save(key, false) })
        }
        assertEquals(
            "One Picker already on stays on",
            true to false,
            migrated {
                it.saveStyle("today")
                it.save("experimental-one-picker", true)
            },
        )
    }

    @Test
    fun callStyleMapsOntoTheTwoCallExperiments() {
        assertEquals(ExperimentalCallStyle.standard, ExperimentalCallStyle.of(callTray = false, livingOrb = false))
        assertEquals(ExperimentalCallStyle.floating, ExperimentalCallStyle.of(callTray = false, livingOrb = true))
        assertEquals(ExperimentalCallStyle.tray, ExperimentalCallStyle.of(callTray = true, livingOrb = false))
        assertEquals("Living Orb wins when both are on", ExperimentalCallStyle.floating, ExperimentalCallStyle.of(callTray = true, livingOrb = true))
        for (style in ExperimentalCallStyle.entries) {
            assertEquals(style, ExperimentalCallStyle.of(style.callTray, style.livingOrb))
            assertFalse("Choosing a style leaves at most one switch on", style.callTray && style.livingOrb)
            assertTrue(style.summary.split(" ").size <= 6)
        }
        assertEquals(listOf("Standard", "Living Orb", "Tray"), ExperimentalCallStyle.entries.map { it.title })
    }

    @Test
    fun livingOrbIsTheDefaultCallStyle() {
        assertTrue(ExperimentalFeature.livingOrb.defaultEnabled)
        assertFalse("Call Style stays in Settings › Experimental › Calls", ExperimentalFeature.livingOrb.isGraduated)
        assertFalse(ExperimentalFeature.callTray.defaultEnabled)
        assertEquals(ExperimentalCallStyle.floating, ExperimentalCallStyle.defaultStyle)
        assertFalse("Reducer tests still start every experiment off", ExperimentalFeatures.testValue(ExperimentalFeature.livingOrb))
    }

    /**
     * Living Orb became the default (Nov 1): every install moves to it once, except a Tray in
     * use. Saved switches can't tell a choice from a default an earlier read wrote, so Standard
     * moves too. Each phase opens its own [SharedStore] over the same bytes, as each launch does.
     */
    @Test
    fun livingOrbBecomesTheDefaultOnceExceptForATrayInUse() {
        val orb = "experimental-living-orb"
        val tray = "experimental-call-tray"
        val legacy = ExperimentalCallStyle.LEGACY_FLOATING_KEY
        fun style(setup: (SharedStore) -> Unit): Pair<ExperimentalCallStyle, Boolean> {
            val bytes = InMemorySharedBytes()
            setup(SharedStore(bytes))
            ExperimentalCallStyle.migrateToLivingOrbDefault(SharedStore(bytes))
            val after = SharedStore(bytes)
            val livingOrb = after.reference(experimental(ExperimentalFeature.livingOrb)).value
            val callTray = after.reference(experimental(ExperimentalFeature.callTray)).value
            return ExperimentalCallStyle.of(callTray = callTray, livingOrb = livingOrb) to after.has(legacy)
        }
        assertEquals("Never chose: Living Orb", ExperimentalCallStyle.floating to false, style { })
        assertEquals("A written default or Standard: Living Orb", ExperimentalCallStyle.floating to false, style { it.save(orb, false) })
        assertEquals(ExperimentalCallStyle.floating to false, style { it.save(orb, false); it.save(tray, false) })
        assertEquals("A Tray in use stays", ExperimentalCallStyle.tray to false, style { it.save(tray, true) })
        assertEquals(ExperimentalCallStyle.tray to false, style { it.save(tray, true); it.save(orb, false) })
        assertEquals("Living Orb won over the tray", ExperimentalCallStyle.floating to false, style { it.save(tray, true); it.save(orb, true) })
        assertEquals(ExperimentalCallStyle.floating to false, style { it.save(orb, true) })
        assertEquals("Floating Call moves to Living Orb", ExperimentalCallStyle.floating to false, style { it.save(legacy, true) })
        assertEquals(ExperimentalCallStyle.floating to false, style { it.save(legacy, false) })
        assertEquals(
            "Call Tray won over Floating Call, so the tray stays",
            ExperimentalCallStyle.tray to false,
            style { it.save(legacy, true); it.save(tray, true) },
        )

        // Once: a choice made afterwards sticks.
        val bytes = InMemorySharedBytes()
        ExperimentalCallStyle.migrateToLivingOrbDefault(SharedStore(bytes))
        SharedStore(bytes).let { it.save(orb, false); it.save(tray, false) }
        ExperimentalCallStyle.migrateToLivingOrbDefault(SharedStore(bytes))
        assertEquals(
            "Standard chosen after the move stays",
            false,
            SharedStore(bytes).reference(SharedKey.appStorage<Boolean?>(orb, null)).value,
        )
    }

    /** `VoiceAudioDemoMode` lives in `:feature:voice`; its half of this test belongs there. */
    @Test
    fun voiceDemoModeRequiresDeveloperMode() {
        withFreshStorage {
            val developerMode = shared(SharedKeys.developerMode)
            val inAppBrowser = shared(experimental(ExperimentalFeature.inAppBrowser))
            val demoMode = shared(SharedKeys.voiceDemoMode)
            inAppBrowser.set(true)
            demoMode.set(true)
            developerMode.set(false)
            assertTrue(ExperimentalFeature.inAppBrowser.isActive(true))
            assertTrue(ExperimentalFeatures.liveValue(ExperimentalFeature.inAppBrowser))
        }
    }
}
