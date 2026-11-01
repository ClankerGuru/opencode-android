package ai.opencode.feature.composer.modelpicker

import ai.opencode.feature.composer.ComposerSwipeOwnership
import ai.opencode.feature.voice.ComposerExpansion
import ai.opencode.server.model.ModelProviderGroup
import ai.opencode.server.model.ModelSearch
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.ambiguousShortNames
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.hypot
import kotlin.math.exp
import kotlin.math.cos
import kotlin.math.PI
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.milliseconds

/**
 * Port of iOS `ModelPickerTests.swift`.
 *
 * The model picker (D-20 round 7 option 1): the list's sections, the level scale the meter and
 * its panel step through, the meter's press, and picks that wait for the next send.
 *
 * Skipped → `:feature:session` (they drive `SessionFeature`'s reducer, which lives there):
 * `openingTheListRefreshesTheCatalogWithoutASheet`, `openingKeepsATypedQueryAndClosingClearsIt`,
 * `picksAndLevelsWaitForTheNextSend`, `holdingARowFavoritesThatProvidersModel`,
 * `plainPickerSheetSortsFavoritesAndKeepsSheetOpenWhenChangingThinking`,
 * `openingPickerWhileLoadingRestartsModelCatalogFetchAndCancelsHungLoad`,
 * `failedModelCatalogRecordsErrorAndRetriesOnReopen`,
 * `theSheetsRecentHoldsStillWhileOpenAndUpdatesNextTime`,
 * `choosingModelAndLevelByTapDragAndRowMenuUpdatesRecentsAndRememberedVariants`,
 * `aFusedPickIsASelectionOnlyWhenItChangesTheModelOrLevel`,
 * `theSheetsLevelMenuPicksAndStaysOpenAndARowTapCloses`,
 * `aPickFromALevelDragThatTickedIsQuietOnRelease`, `aRowLevelIsASelectionOnlyWhenTheLevelChanges`,
 * and the reducer half of `searchParsesTrailingLevelAndReturnPicksBestMatchAtThatLevel`.
 *
 * Dropped: `turningOffCustomModelPickerUnregistersLevelTunables` (no Tuning Panel store on
 * Android: the tunables are baked constants), `searchableSheetsAvoidForcedNavigationBarDrawerOnMac`
 * (SwiftUI `SearchFieldPlacement` on Mac and an iOS source scan).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelPickerTests {
    private fun model(modelID: String, providerID: String, name: String, variants: List<String> = emptyList()) =
        OpenCodeModel(modelID = modelID, providerID = providerID, name = name, variants = variants)

    private val cirrusZen = model("cirrus-4", "opencode", "Cirrus 4", listOf("low", "high"))
    private val mini = model("cirrus-4-mini", "opencode", "Cirrus 4 Mini")
    private val cirrusRouter = model("vendor/cirrus-4", "openrouter", "Cirrus 4", listOf("low", "medium", "high", "max"))
    private val basalt = model("basalt-pro", "halcyon", "Basalt Pro")

    // MARK: Sections

    @Test fun favoritesThenOneSectionPerProviderInTheServersOrder() {
        val sections = ModelPickerSection.sections(listOf(cirrusZen, cirrusRouter, mini, basalt), setOf(basalt.id, cirrusRouter.id))
        assertEquals(listOf("Favorites", "OpenCode Zen", "OpenRouter", "Halcyon"), sections.map { it.title })
        assertEquals(listOf(cirrusRouter, basalt), sections[0].models)
        assertEquals(listOf(cirrusZen, mini), sections[1].models)
        assertEquals("A favorite stays in its provider's section too", listOf(cirrusRouter), sections[2].models)
    }

    @Test fun aModelSeveralProvidersOfferIsListedUnderEach() {
        val sections = ModelPickerSection.sections(listOf(cirrusZen, cirrusRouter), emptySet())
        assertEquals(listOf("OpenCode Zen", "OpenRouter"), sections.map { it.title })
        assertEquals(listOf("Cirrus 4", "Cirrus 4"), sections.flatMap { it.models }.map { it.name })
    }

    @Test fun searchDropsFavoritesAndMatchesNamesAndProviders() {
        val models = listOf(cirrusZen, cirrusRouter, mini, basalt)
        val byName = ModelPickerSection.sections(models, setOf(basalt.id), filter = "basalt")
        assertEquals(listOf("Halcyon"), byName.map { it.title })
        val byProvider = ModelPickerSection.sections(models, emptySet(), filter = "OpenRouter")
        assertEquals(listOf(cirrusRouter), byProvider.flatMap { it.models })
        assertTrue(ModelPickerSection.sections(models, emptySet(), filter = "zzz").isEmpty())
    }

    // MARK: Search (OC-331)

    private val sonnet45 = model("claude-sonnet-4-5-20250929", "anthropic", "Claude Sonnet 4.5")
    private val sonnet4 = model("claude-sonnet-4-20250514", "anthropic", "Claude Sonnet 4")
    private val opus = model("claude-opus-4-1", "anthropic", "Claude Opus 4.1")
    private val zenSonnet = model("claude-sonnet-4-5", "opencode", "Claude Sonnet 4.5")
    private val zenMini = model("gpt-5-mini", "opencode", "GPT-5 Mini")
    private val kimi = model("kimi-k2", "opencode-go", "Kimi K2")
    private val gpt5 = model("gpt-5", "openai", "GPT-5")
    private val openAIMini = model("gpt-5-mini", "openai", "GPT-5 Mini")
    private val routerOpus = model("anthropic/claude-opus-4.1", "openrouter", "Claude Opus 4.1")
    private val gemini = model("gemini-2.5-pro", "google", "Gemini 2.5 Pro")
    private val catalog: List<OpenCodeModel>
        get() = listOf(sonnet45, sonnet4, opus, zenSonnet, zenMini, kimi, gpt5, openAIMini, routerOpus, gemini)

    private fun search(query: String, favorites: Set<String> = emptySet()): List<OpenCodeModel> =
        ModelSearch.rank(catalog, query, favorites)

    @Test fun aProvidersNameListsItsModels() {
        assertEquals("OpenCode Zen and OpenCode Go, in the server's order", listOf(zenSonnet, zenMini, kimi), search("opencode"))
        assertEquals(listOf(zenSonnet, zenMini), search("OpenCode Zen"))
        assertEquals("OpenRouter's Anthropic models by their IDs, last", listOf(sonnet45, sonnet4, opus, routerOpus), search("anthropic"))
    }

    @Test fun wordsMatchInAnyOrderAcrossNameAndProvider() {
        assertEquals("Not Sonnet 4, whose ID only has a date with 4 and 5 in it", listOf(sonnet45, zenSonnet), search("son 4.5"))
        assertEquals(listOf(sonnet45, zenSonnet), search("4.5 son"))
        assertEquals(listOf(zenMini, openAIMini), search("gpt mini"))
        assertEquals(listOf(opus, routerOpus), search("anthropic opus"))
        assertEquals(listOf(opus, routerOpus), search("opus anthropic"))
        assertEquals(listOf(zenSonnet), search("zen sonnet"))
        assertEquals(listOf(zenMini), search("opencode gpt"))
    }

    @Test fun betterMatchesComeFirstThenFavorites() {
        assertEquals("A whole word before Gemini, which only contains it", zenMini, search("mini").first())
        assertEquals(gemini, search("mini").last())
        assertEquals(listOf(openAIMini, zenMini), search("gpt mini", favorites = setOf(openAIMini.id)))
        assertEquals("Equal matches keep the server's order", listOf(zenMini, gpt5, openAIMini), search("gpt"))
        assertEquals("Three letters may skip letters inside a word of the name", listOf(sonnet45, sonnet4, zenSonnet), search("snt"))
        assertEquals("Run together like the desktop's search", listOf(zenMini, openAIMini), search("gpt5mini"))
        assertEquals("The 5 in 4.5, not one inside Sonnet 4's date", listOf(sonnet45, zenSonnet), search("sonnet 5"))
        assertTrue("A number matches only where one starts", search("sonnet 0929").isEmpty())
    }

    @Test fun emptyAndPunctuationQueriesKeepTheList() {
        assertEquals(catalog, search(""))
        assertEquals(catalog, search("  ·  "))
        assertEquals("claude sonnet 4.5", ModelSearch.normalize("Claude Sonnet 4.5"))
        assertEquals("claude sonnet 4.5", ModelSearch.normalize("claude-sonnet-4-5"))
        assertEquals("gemini 2 5 pro", ModelSearch.normalize("Gémini 2,5 – Pro"))
    }

    /** iOS reads `filteredModels`/`modelSections` off `SessionFeature.State`; here off [ModelPickerState], which the session builds. */
    @Test fun bothPickersShareTheSearch() {
        val sections = ModelPickerSection.sections(catalog, setOf(opus.id), filter = "anthropic opus")
        assertEquals(listOf("Anthropic", "OpenRouter"), sections.map { it.title })
        val plain = ModelPickerState(models = catalog, modelFilter = "anthropic opus")
        assertEquals(listOf(opus, routerOpus), plain.filteredModels)
        assertEquals(listOf("Anthropic", "OpenRouter"), plain.modelSections.map { it.title })
    }

    // MARK: Level scale

    @Test fun levelsRunFromTheDefaultUpThroughTheSortedVariants() {
        val scale = ModelLevelScale(listOf("high", "default", "low", "max", "medium"))
        assertEquals(listOf(null, "low", "medium", "high", "max"), scale.levels)
        assertEquals(listOf("low", "medium", "high", "max"), scale.variants)
        assertTrue(scale.isAdjustable)
        assertFalse(ModelLevelScale(emptyList()).isAdjustable)
        assertEquals(0, scale.index("default"))
        assertEquals("A level the model no longer offers reads as its default", 0, scale.index("ultra"))
        assertEquals("max", scale.stepped(from = "high", by = 1))
        assertEquals("max", scale.stepped(from = "max", by = 1))
        assertNull(scale.stepped(from = null, by = -1))
        assertEquals("Default", ModelLevelScale.label(null))
        assertEquals("Extra High", ModelLevelScale.label("xhigh"))
    }

    @Test fun scrubbingStepsOneLevelPerThirtyPointsFromWhereTheHoldBegan() {
        val scale = ModelLevelScale(listOf("low", "medium", "high", "max"))
        assertEquals("Under half a step stays put", 3, scale.scrubIndex(from = 3, translation = 14.0))
        assertEquals(4, scale.scrubIndex(from = 3, translation = 16.0))
        assertEquals(1, scale.scrubIndex(from = 3, translation = -61.0))
        assertEquals(4, scale.scrubIndex(from = 3, translation = 400.0))
        assertEquals(0, scale.scrubIndex(from = 3, translation = -400.0))
    }

    // MARK: Swipe ownership

    /**
     * A drag over the composer that began on the level stays the level's to its last callback,
     * even after the level's own gesture has let go.
     */
    @Test fun aDragKeepsTheOwnerItStartedWith() {
        val ownership = ComposerSwipeOwnership()
        val start = Offset(40f, 10f)
        assertTrue(ownership.yields(start) { true })
        assertTrue("The level let go first: still its drag", ownership.yields(start) { false })
        ownership.ended()
        assertFalse("The next drag decides afresh", ownership.yields(start) { false })
        assertFalse(ownership.yields(Offset(41f, 10f)) { false })
        assertTrue("A new start decides afresh", ownership.yields(Offset(90f, 10f)) { true })
    }

    /**
     * The list's rests: medium about half the large reach (never under 300 pt), a throw settling
     * at the nearer rest or closing well below medium, and a pull past large rubber-banding.
     */
    @Test fun theListRestsLikeASheetsDetents() {
        val rests = ModelListDetent.Rests(large = 800.0)
        assertEquals(400.0, rests.medium, 0.0)
        assertEquals(300.0, ModelListDetent.Rests(large = 420.0).medium, 0.0)
        assertEquals("Never above large", 200.0, ModelListDetent.Rests(large = 200.0).medium, 0.0)
        assertEquals(ModelListDetent.Large, ModelListDetent.rest(projected = 700.0, rests = rests))
        assertEquals(ModelListDetent.Medium, ModelListDetent.rest(projected = 520.0, rests = rests))
        assertEquals(ModelListDetent.Medium, ModelListDetent.rest(projected = 230.0, rests = rests))
        assertNull("Thrown well below medium, it closes", ModelListDetent.rest(projected = 200.0, rests = rests))
        assertEquals(300.0, ModelListDetent.reach(resting = 400.0, drag = 100.0, full = 800.0), 0.0)
        assertEquals(0.0, ModelListDetent.reach(resting = 400.0, drag = 500.0, full = 800.0), 0.0)
        val pulled = ModelListDetent.reach(resting = 800.0, drag = -400.0, full = 800.0)
        assertTrue("Past large it gives a little, never more than 48 pt", pulled > 800 && pulled < 848)
    }

    /**
     * The picker's knobs default to the polished behaviour. iOS also checks the Tuning Panel's
     * `modelPicker` group, its Today/Polished/Snappy presets and keys: n/a, Android has no
     * Tuning Panel (the tunables are baked constants).
     */
    @Test fun pickerTunablesDefaultToWhatShipped() {
        val t = ModelPickerTunables
        assertEquals("spring(response: 0.42, dampingFraction: 0.9)", 0.42 to 0.1, t.glassOpen.duration to t.glassOpen.bounce)
        assertEquals(0.3 to 0.0, t.glassClose.duration to t.glassClose.bounce)
        assertEquals(0.42 to 0.06, t.restSpring.duration to t.restSpring.bounce)
        assertEquals(0.44 to 0.08, t.levelOpen.duration to t.levelOpen.bounce)
        assertEquals(0.36 to 0.0, t.levelClose.duration to t.levelClose.bounce)
        assertEquals(0.26 to 0.12, t.levelStep.duration to t.levelStep.bounce)
        assertEquals(10f..50f, ComposerExpansion.barFade)
        assertEquals(34f..150f, ComposerExpansion.contentFade)
        assertEquals(0.6, ComposerExpansion.settle, 0.0)
        assertEquals(420.milliseconds, ModelPickerMotion.settle(reduceMotion = false))
    }

    /**
     * A drag that begins mid-spring starts from where the glass is drawn, not from the rest it's
     * heading for, and keeps that start; a drag the system cancels (no throw) settles at the
     * nearer rest, never in between.
     */
    @Test fun aDragCatchesTheGlassWhereItIsAndACancelledOneSettles() {
        val first = ModelListDetent.drag(null, drawnReach = 523.0, translation = 4.0)
        assertEquals("Caught mid-spring at 523, not at a rest", ModelListDetent.Drag(start = 523.0, translation = 4.0), first)
        assertEquals("No jump", 519.0, ModelListDetent.reach(resting = first.start, drag = first.translation, full = 800.0), 0.0)
        val later = ModelListDetent.drag(first, drawnReach = 610.0, translation = -30.0)
        assertEquals("Later moves keep the catch", 523.0, later.start, 0.0)
        val rests = ModelListDetent.Rests(large = 800.0)
        assertEquals("Let go (or cancelled) nearer medium", ModelListDetent.Medium, ModelListDetent.rest(projected = 523.0 - 0, rests = rests))
        assertEquals(ModelListDetent.Large, ModelListDetent.rest(projected = 700.0 - 0, rests = rests))
    }

    /**
     * A drag the system cancels settles from where the finger left the glass, not from where it
     * caught it: dragged from Half nearly to Full, it stays Full.
     */
    @Test fun aCancelledDragSettlesFromWhereTheFingerLeftIt() {
        val rests = ModelListDetent.Rests(large = 800.0)
        val nearlyFull = ModelListDetent.Drag(start = 400.0, translation = -360.0)
        assertEquals(ModelListDetent.Large, ModelListDetent.settle(nearlyFull, predictedTranslation = null, rests = rests))
        val barely = ModelListDetent.Drag(start = 400.0, translation = -60.0)
        assertEquals(ModelListDetent.Medium, ModelListDetent.settle(barely, predictedTranslation = null, rests = rests))
        val downFar = ModelListDetent.Drag(start = 400.0, translation = 260.0)
        assertNull("Left well below medium, it closes", ModelListDetent.settle(downFar, predictedTranslation = null, rests = rests))
        assertEquals(
            "A throw's own prediction wins when there is one",
            ModelListDetent.Medium,
            ModelListDetent.settle(nearlyFull, predictedTranslation = 0.0, rests = rests),
        )
    }

    /** TalkBack's grabber reads and adjusts the rest like a sheet's (iOS `.increment`/`.decrement` → +1/−1). */
    @Test fun theGrabberAdjustsTheRest() {
        assertEquals("Half", ModelListDetent.Medium.accessibilityValue)
        assertEquals("Full", ModelListDetent.Large.accessibilityValue)
        assertEquals(ModelListDetent.Large, ModelListDetent.Medium.adjusted(1))
        assertEquals(ModelListDetent.Medium, ModelListDetent.Large.adjusted(-1))
        assertEquals(ModelListDetent.Large, ModelListDetent.Large.adjusted(1))
    }

    /** One restore at a time: scheduling another or cancelling drops it. */
    @Test fun aFocusRestoreRunsOnceUnlessCancelled() = runTest {
        val restore = ComposerFocusRestore(this)
        val runs = mutableListOf<String>()
        restore.schedule(after = 30.milliseconds) { runs += "first" }
        restore.schedule(after = 30.milliseconds) { runs += "second" }
        advanceTimeBy(150.milliseconds)
        assertEquals(listOf("second"), runs)
        restore.schedule(after = 30.milliseconds) { runs += "third" }
        restore.cancel()
        advanceTimeBy(150.milliseconds)
        assertEquals(listOf("second"), runs)
    }

    @Test fun levelScrubberTunablesDefaultToKitPicks() {
        assertTrue(!LevelScrubberStyle.PullOut.coversComposer && LevelScrubberStyle.BarPanel.coversComposer)
        assertEquals("Kit's pick (build 384): the scrubber pulled out of the glyph", LevelScrubberStyle.PullOut, LevelScrubberTunables.style)
        assertEquals("Kit picked D-65 option 3: Filled Arc", LevelIconStyle.FilledArc, LevelScrubberTunables.iconStyle)
        assertEquals("Kit picked placing the level icon next to the model picker", LevelPlacement.BesideModel, LevelScrubberTunables.placement)
    }

    /**
     * D-65 option 3: level j of n fills j/n of the arc, so the lowest is never empty and the
     * highest always full, at any count; Default fills to the middle, dimmed. iOS checks the
     * `Shape`'s path (empty at fill 0, centred in its box); Android draws the arc straight into a
     * `DrawScope` ([LevelArc.draw]), so the check reads the drawn pixels' bounds instead.
     */
    /** The pixel bounds [LevelArc.draw] covers in a 20×20 box (a 1 px stroke, close to the iOS path), or null when nothing is drawn. */
    private fun drawnBounds(fill: Double): androidx.compose.ui.geometry.Rect? {
        val image = androidx.compose.ui.graphics.ImageBitmap(20, 20)
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
            androidx.compose.ui.unit.Density(1f),
            androidx.compose.ui.unit.LayoutDirection.Ltr,
            androidx.compose.ui.graphics.Canvas(image),
            androidx.compose.ui.geometry.Size(20f, 20f),
        ) { LevelArc.draw(this, fill = fill, span = 240.0, color = androidx.compose.ui.graphics.Color.Black, stroke = 1f) }
        val pixels = IntArray(400)
        image.readPixels(pixels)
        val lit = pixels.indices.filter { (pixels[it] ushr 24) != 0 }
        if (lit.isEmpty()) return null
        val xs = lit.map { it % 20 }
        val ys = lit.map { it / 20 }
        return androidx.compose.ui.geometry.Rect(xs.min().toFloat(), ys.min().toFloat(), xs.max() + 1f, ys.max() + 1f)
    }

    @Test fun theArcFillsToTheLevelAtAnyCount() {
        for (levels in 1..6) {
            val count = levels + 1
            assertTrue(LevelArc.pose(index = 1, count = count).fill > 0)
            assertEquals(LevelArc.Pose(1.0, 1.0), LevelArc.pose(index = levels, count = count))
            val fills = (1..levels).map { LevelArc.pose(index = it, count = count).fill }
            assertTrue("Each level fills further", fills == fills.sorted() && fills.toSet().size == levels)
            val standard = LevelArc.pose(index = 0, count = count)
            assertTrue("Default reads dimmed, about halfway", standard.lit < 1 && standard.fill > 0.5 - 0.001 && standard.fill <= 1)
        }
        assertEquals(LevelArc.Pose(0.625, 0.45), LevelArc.pose(index = 0, count = 5))
        assertEquals("From bottom left, over the top, to bottom right", 150.0, LevelArc.start(span = 240.0), 0.0)
        assertNull("Nothing drawn at fill 0", drawnBounds(fill = 0.0))
        val full = drawnBounds(fill = 1.0)!!
        assertTrue("Centred in its box: $full", abs((full.left + full.right) / 2f - 10f) < 0.6f && full.top > 1f && full.bottom < 19f)
        assertEquals(2.45, LevelScrubberTunables.arcStroke, 0.0)
        assertEquals(240.0, LevelScrubberTunables.arcSpan, 0.0)
    }

    @Test fun theBackdropIsStrongestOverTheComposerAndGoneAtBothEnds() {
        val band = progressiveBlurStrengths(ProgressiveBlurProfile.Band(0.46, 0.66), samples = 50)
        assertEquals(0.0, band.first(), 0.0)
        assertEquals(0.0, band.last(), 0.0)
        assertEquals(1.0, band[28], 0.0)
    }

    /**
     * The search clears from the panel's own closed state, whatever the timing: a settled close
     * and a reset reach it once each, and a reopen mid-fold never does, so it can't be skipped or
     * come too early.
     */
    @Test fun thePanelReachesClosedOnceWhateverTheTiming() = runTest {
        val panel = GrowingPanel(this)
        var closings = 0
        backgroundScope.launch {
            var wasShown = panel.isShown
            while (isActive) {
                if (wasShown && !panel.isShown) closings += 1
                wasShown = panel.isShown
                delay(5)
            }
        }
        panel.open()
        delay(30)
        panel.close(reduceMotion = true)
        assertTrue("Still folding", panel.isShown)
        delay(320)
        assertFalse(panel.isShown)
        assertEquals("A settled close reaches closed", 1, closings)
        panel.open()
        delay(30)
        panel.close(reduceMotion = true)
        delay(100)
        panel.open()
        delay(320)
        assertTrue("Reopened mid-fold: it stays open", panel.isOpen)
        assertEquals("…and never reached closed", 1, closings)
        panel.reset()
        delay(30)
        assertEquals("A reset reaches closed at once", 2, closings)
    }

    // MARK: - Fused + Stack Model Picker (OC-397)

    private val opus55 = model("claude-opus-5-5", "anthropic", "Claude Opus 5.5", listOf("low", "medium", "high", "max"))
    private val sonnet55Anthropic = model("claude-sonnet-5-5", "anthropic", "Claude Sonnet 5.5", listOf("low", "medium", "high"))
    private val sonnet55Zen = model("claude-sonnet-5-5", "opencode", "Claude Sonnet 5.5", listOf("low", "medium", "high"))
    private val argon = model("gemini-4-argon", "console-google", "Gemini 4 Argon", listOf("low", "medium", "high"))
    private val astra = model("gpt-6-astra", "openai", "GPT-6 Astra", listOf("low", "medium", "high", "xhigh"))
    private val haiku = model("claude-haiku-4-5", "anthropic", "Claude Haiku 4.5")

    @Test fun shortNamesStripRedundantVendorWordsAndDisambiguateSharedShortNames() {
        assertEquals("Opus 5.5", opus55.shortName)
        assertEquals("Sonnet 5.5", sonnet55Anthropic.shortName)
        assertEquals("Single-word family before a version digit stays intact", "Gemini 4 Argon", argon.shortName)
        val googleArgon = model("gemini-4-argon", "console-google", "Google Gemini 4 Argon")
        assertEquals("Redundant provider word is stripped", "Gemini 4 Argon", googleArgon.shortName)
        assertEquals("Hyphenated GPT-6 stays whole", "GPT-6 Astra", astra.shortName)
        val zenModel = model("opencode-zen", "opencode", "OpenCode Zen")
        assertEquals("Zen", zenModel.shortName)
        val models = listOf(opus55, sonnet55Anthropic, sonnet55Zen, argon, astra)
        assertEquals("Only Sonnet 5.5 is offered by two providers", setOf("Sonnet 5.5"), models.ambiguousShortNames)
        assertEquals("Google", ModelProviderGroup.canonicalVendorTitle("console-google"))
        assertEquals("The old picker keeps main's titles", "Console Google", ModelProviderGroup.title("console-google"))
        assertEquals("Fireworks Ai", ModelProviderGroup.title("fireworks-ai"))
        assertEquals("Fireworks AI", ModelProviderGroup.canonicalVendorTitle("fireworks-ai"))
    }

    @Test fun fusedSectionsOrderFavoritesThenThreeRecentThenAllModelsAlphabetically() {
        val models = listOf(opus55, sonnet55Anthropic, argon, astra, haiku)
        val sections = ModelPickerSection.fusedSections(
            models = models,
            favorites = setOf(opus55.id),
            recents = listOf(opus55.id, argon.id, astra.id, haiku.id, sonnet55Anthropic.id),
            recentLimit = 3,
        )
        assertEquals(listOf("Favorites", "Recent", "All Models"), sections.map { it.title })
        assertEquals(listOf(opus55), sections[0].models)
        assertEquals("Up to 3 recents not already in Favorites", listOf(argon, astra, haiku), sections[1].models)
        assertEquals("Remaining catalog models appear under All Models", listOf(sonnet55Anthropic), sections[2].models)
    }

    /**
     * New Model Picker (OC-469): Recent sits between Favorites and the providers, most recent
     * first, without favorites, up to its limit, and goes while searching, as Favorites does.
     */
    @Test fun theSheetsRecentSitsAfterFavoritesWithoutThemUpToItsLimit() {
        val models = listOf(opus55, sonnet55Anthropic, argon, astra, haiku)
        val recents = listOf(opus55.id, "gone/model", argon.id, astra.id, haiku.id, sonnet55Anthropic.id)
        val sections = ModelPickerSection.sections(models, setOf(opus55.id), recents = recents, recentLimit = 3)
        assertEquals(listOf("Favorites", "Recent", "Anthropic", "Console Google", "OpenAI"), sections.map { it.title })
        assertEquals(listOf(opus55), sections[0].models)
        assertEquals("Up to 3, most recent first, skipping favorites and models no longer listed", listOf(argon, astra, haiku), sections[1].models)
        assertEquals("Providers still list every model", listOf(opus55, sonnet55Anthropic, haiku), sections[2].models)

        val searching = ModelPickerSection.sections(models, setOf(opus55.id), filter = "argon", recents = recents, recentLimit = 3)
        assertTrue(
            "Searching drops Recent with Favorites",
            searching.none { it.kind == ModelPickerSection.Kind.Recent || it.kind == ModelPickerSection.Kind.Favorites },
        )
        assertNotEquals(ModelPickerSection.Kind.Recent, ModelPickerSection.sections(models, emptySet(), recents = recents, recentLimit = 0).firstOrNull()?.kind)
        assertNotEquals(ModelPickerSection.Kind.Recent, ModelPickerSection.sections(models, emptySet(), recents = emptyList(), recentLimit = 3).firstOrNull()?.kind)
        assertEquals(
            "Without recents (the custom picker's list) nothing changes",
            listOf("Anthropic", "Console Google", "OpenAI"),
            ModelPickerSection.sections(models, emptySet()).map { it.title },
        )
    }

    /** The parse half; the reducer half (`.modelSearchSubmitted`) is skipped → `:feature:session`. */
    @Test fun searchParsesTrailingLevelAndReturnPicksBestMatchAtThatLevel() {
        val parsedHigh = ModelSearch.parseLevelQuery("argon high")
        assertTrue(parsedHigh.terms == listOf("argon") && parsedHigh.level == "high")
        val parsedMax = ModelSearch.parseLevelQuery("opus max")
        assertTrue(parsedMax.terms == listOf("opus") && parsedMax.level == "max")
        val parsedPlain = ModelSearch.parseLevelQuery("high")
        assertTrue("Single word stays a model query", parsedPlain.terms == listOf("high") && parsedPlain.level == null)
    }

    @Test fun levelStackAndRowMenuStateAndFrozenSectionsDuringFold() = runTest {
        val anchors = ModelPickerAnchors(animationScope())
        val models = listOf(opus55, argon, astra)
        anchors.frozenSections = ModelPickerSection.fusedSections(models = models, favorites = setOf(opus55.id), recents = listOf(argon.id))
        assertEquals(3, anchors.frozenSections?.size)

        // Open vertical level stack on Opus 5.5 at High (index 3 of [nil, low, medium, high, max]).
        anchors.openLevelStack(
            kind = FusedLevelStackState.SourceKind.Row,
            model = opus55,
            level = "high",
            anchorX = 320f,
            anchorY = 400f,
            rightEdgeX = 350f,
            tapMode = true,
            reduceMotion = false,
        )
        assertEquals(3, anchors.levelStack?.currentIndex)
        assertEquals(true, anchors.levelStack?.tapMode)

        // Drag up by one stackGap (40 pt) -> index 4 ("max").
        val steppedUp = anchors.updateLevelStackDrag(deltaY = 40.0)
        assertEquals(4, steppedUp)
        assertEquals(4, anchors.levelStack?.currentIndex)

        // Drag down by two stackGaps (-80 pt from start 3) -> index 1 ("low").
        val steppedDown = anchors.updateLevelStackDrag(deltaY = -80.0)
        assertEquals(1, steppedDown)
        assertEquals(1, anchors.levelStack?.currentIndex)

        anchors.closeLevelStack(commit = true, reduceMotion = true)
        assertEquals(true, anchors.levelStack?.isClosing)

        // Long-press row menu opens and closes. iOS reads the alpha's model value: here the
        // Animatable's target, set once the fade-in it interrupts has let go (next dispatch).
        anchors.openRowMenu(model = argon, rowFrame = rect(16f, 240f, 340f, 48f), reduceMotion = true)
        assertEquals(argon, anchors.rowMenu?.model)
        anchors.closeRowMenu(reduceMotion = true)
        runCurrent()
        assertEquals(0f, anchors.rowMenuAlpha.targetValue, 0f)

        anchors.resetFusedState()
        assertNull(anchors.frozenSections)
        assertNull(anchors.levelStack)
        assertNull(anchors.rowMenu)
    }

    @Test fun onlyTheFusedPickerReadsALevelWordInASearch() {
        val models = listOf(opus55, argon)
        assertTrue("The plain picker searches every word, as on main", ModelSearch.rank(models, "argon high").isEmpty())
        assertEquals(listOf(argon), ModelSearch.rank(models, "argon high", parseLevel = true))
        assertEquals(ModelSearch.rank(models, "argon"), ModelSearch.rank(models, "argon", parseLevel = true))
        assertTrue(ModelPickerSection.sections(models, emptySet(), filter = "argon high").isEmpty())
        assertEquals(listOf(argon), ModelPickerSection.fusedSections(models = models, favorites = emptySet(), filter = "argon high").firstOrNull()?.models)
    }

    @Test fun foldingOverlaysStopOwningTouchesAndStaleFramesClear() = runTest {
        val anchors = ModelPickerAnchors(animationScope())
        anchors.openLevelStack(
            kind = FusedLevelStackState.SourceKind.Row, model = opus55, level = "high", anchorX = 320f, anchorY = 400f,
            rightEdgeX = 350f, tapMode = true, reduceMotion = true,
        )
        assertTrue(anchors.isLevelStackActive && anchors.isOverlayActive)
        anchors.closeLevelStack(reduceMotion = true)
        assertTrue("Still mounted, so its fold animates", anchors.levelStack != null)
        assertTrue("A folding stack no longer blocks the rows", !anchors.isLevelStackActive && !anchors.isOverlayActive)

        anchors.openRowMenu(model = argon, rowFrame = rect(16f, 240f, 340f, 48f), reduceMotion = true)
        assertTrue(anchors.isRowMenuActive)
        anchors.closeRowMenu(reduceMotion = true)
        assertEquals("Still mounted, so its fold animates", true, anchors.rowMenu?.isClosing)
        assertTrue("A folding menu no longer blocks the rows", !anchors.isRowMenuActive && !anchors.isOverlayActive)

        anchors.chipFrame = rect(60f, 780f, 120f, 44f)
        anchors.chipNameFrame = rect(60f, 792f, 70f, 20f)
        anchors.chipLevelFrame = rect(135f, 792f, 40f, 20f)
        anchors.levelControl = anchors.chipLevelFrame
        anchors.clearChipLevelFrame()
        assertTrue(anchors.chipLevelFrame == Rect.Zero && anchors.levelControl == Rect.Zero)
        assertTrue("A model with no level keeps its name's frame", anchors.chipNameFrame != Rect.Zero)
        anchors.clearChipFrames()
        assertTrue(anchors.chipFrame == Rect.Zero && anchors.chipNameFrame == Rect.Zero)

        anchors.rowFrames[argon.id] = rect(16f, 240f, 340f, 48f)
        anchors.rowNameFrames[argon.id] = rect(50f, 254f, 120f, 20f)
        anchors.rowLevelFrames[argon.id] = rect(300f, 254f, 40f, 20f)
        anchors.resetFusedState()
        assertTrue(anchors.rowFrames.isEmpty() && anchors.rowNameFrames.isEmpty() && anchors.rowLevelFrames.isEmpty())
    }

    @Test fun fusedTunablesMatchKitDefaultsAndPerFrameTracesHaveNoJumpOverOneAndAHalfPoints() {
        val f = FusedModelPickerTunables
        assertFalse("Provider logo on chip is off by default", f.chipProviderLogo)
        assertEquals(0.34 to 0.12, f.open.duration to f.open.bounce)
        assertEquals(0.25 to 0.0, f.close.duration to f.close.bounce)
        assertEquals(0.32 to 0.08, f.flight.duration to f.flight.bounce)
        assertEquals(470.0, f.mediumMax, 0.0)
        assertEquals(200.0, f.chipMax, 0.0)
        assertEquals(40.0, f.stackGap, 0.0)
        assertEquals(0.65, f.focusDim, 0.0)
        assertTrue(f.backdropDim == 0.0 && f.backdropBlur == 0.0)

        // Verify 120 Hz trajectories for panel growth and label flight have no jump or handoff snap > 1.5 pt.
        val openPositions = FusedPickerMotionTrace.springPositions(duration = f.open.duration, bounce = f.open.bounce, fps = 120.0)
        val closePositions = FusedPickerMotionTrace.springPositions(duration = f.close.duration, bounce = f.close.bounce, fps = 120.0)
        val flightPositions = FusedPickerMotionTrace.springPositions(duration = f.flight.duration, bounce = f.flight.bounce, fps = 120.0)

        val composerTopY = 760f
        val panelOpenTopY = 338f
        val openPanelPoints = openPositions.map { t -> Offset(16f, composerTopY + (panelOpenTopY - composerTopY) * t.toFloat()) }
        val closePanelPoints = closePositions.map { t -> Offset(16f, panelOpenTopY + (composerTopY - panelOpenTopY) * t.toFloat()) }
        val openJump = FusedPickerMotionTrace.maxDiscontinuity(openPanelPoints, Offset(16f, composerTopY), Offset(16f, panelOpenTopY))
        val closeJump = FusedPickerMotionTrace.maxDiscontinuity(closePanelPoints, Offset(16f, panelOpenTopY), Offset(16f, composerTopY))
        assertTrue("Panel growth has no jump over 1.5 pt: $openJump", openJump < 1.5f)
        assertTrue("Panel fold has no jump over 1.5 pt: $closeJump", closeJump < 1.5f)

        // iOS lifts the label 6 pt mid-flight (a constant there, a parameter here).
        val lift = 6f
        val fromRect = rect(68f, 780f, 84f, 20f)
        val toRect = rect(56f, 420f, 92f, 22f)
        val openFlightPoints = flightPositions.map { t ->
            FusedPickerMotionTrace.labelPoint(fromRect, toRect, t.toFloat(), FusedLabelFlight.Direction.Open, lift)
        }
        val closeFlightPoints = flightPositions.map { t ->
            FusedPickerMotionTrace.labelPoint(toRect, fromRect, t.toFloat(), FusedLabelFlight.Direction.Close, lift)
        }
        val openFlightJump = FusedPickerMotionTrace.maxDiscontinuity(
            openFlightPoints, Offset(fromRect.left, fromRect.center.y), Offset(toRect.left, toRect.center.y),
        )
        val closeFlightJump = FusedPickerMotionTrace.maxDiscontinuity(
            closeFlightPoints, Offset(toRect.left, toRect.center.y), Offset(fromRect.left, fromRect.center.y),
        )
        assertTrue("Open label flight has no jump over 1.5 pt: $openFlightJump", openFlightJump < 1.5f)
        assertTrue("Close label flight has no jump over 1.5 pt: $closeFlightJump", closeFlightJump < 1.5f)
    }

    private fun rect(x: Float, y: Float, width: Float, height: Float) = Rect(Offset(x, y), Size(width, height))

    /** The anchors' fades run on `Animatable`s, which need a frame clock: a 60 Hz one on virtual time. */
    private fun TestScope.animationScope(): CoroutineScope {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
                delay(16)
                return onFrame(testScheduler.currentTime * 1_000_000)
            }
        }
        return CoroutineScope(backgroundScope.coroutineContext + clock)
    }
}

// iOS 89231d5f moved these test-only samplers out of `FusedPickerMotionTrace` into the tests.

/** The exact spring position `x(t)` for a `Spring(duration:bounce:)` from 0 to 1 at [fps] Hz. */
private fun FusedPickerMotionTrace.springPositions(duration: Double, bounce: Double, fps: Double = 120.0, totalSeconds: Double = 0.7): List<Double> {
    val omega = (2 * PI) / max(duration, 0.01)
    val zeta = max(if (bounce >= 0) 1 - bounce else 1 / (1 + abs(bounce)), 0.01)
    val dt = 1.0 / fps
    val steps = roundHalfAwayFromZero(totalSeconds * fps).toInt()
    var x = 0.0
    var v = 0.0
    val target = 1.0
    val out = mutableListOf(0.0)
    repeat(steps) {
        val x0 = x - target
        val v0 = v
        if (abs(zeta - 1) < 1e-4) {
            val decay = exp(-omega * dt)
            val a = v0 + omega * x0
            x = target + (x0 + a * dt) * decay
            v = (v0 - omega * a * dt) * decay
        } else if (zeta < 1) {
            val wd = omega * sqrt(1 - zeta * zeta)
            val decay = exp(-zeta * omega * dt)
            val c = cos(wd * dt)
            val s = sin(wd * dt)
            x = target + decay * (x0 * c + ((v0 + zeta * omega * x0) / wd) * s)
            v = decay * (v0 * c - ((zeta * omega * v0 + omega * omega * x0) / wd) * s)
        }
        out += x
    }
    return out
}

/** The worst handoff snap or mid-flight jump spike across a point trace. */
private fun FusedPickerMotionTrace.maxDiscontinuity(points: List<Offset>, expectedStart: Offset, expectedEnd: Offset): Float {
    val first = points.firstOrNull() ?: return 0f
    val last = points.last()
    val startSnap = hypot(first.x - expectedStart.x, first.y - expectedStart.y)
    val endSnap = hypot(last.x - expectedEnd.x, last.y - expectedEnd.y)
    val steps = points.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }
    var maxSpike = 0f
    if (steps.size >= 3) {
        for (idx in 1 until steps.size - 1) {
            val envelope = max(steps[idx - 1], steps[idx + 1]) * 1.5f
            if (steps[idx] > envelope) maxSpike = max(maxSpike, steps[idx] - envelope)
        }
    }
    return maxOf(startSnap, endSnap, maxSpike)
}
