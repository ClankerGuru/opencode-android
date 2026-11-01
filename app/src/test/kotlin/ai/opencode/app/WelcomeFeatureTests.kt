package ai.opencode.app

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.settings.onboarding.WelcomeFeature
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `WelcomeFeatureTests.swift` (7f411b1c), the first-run halves: the welcome's
 * requests run the classic screen's pairing.
 *
 * Skipped: `showWelcomeThenCloseLeavesThePairedServerAlone`, `previewScanOpensTheScannerOverHome`
 * and `previewPasteOfAnEmptyClipboardSaysSoInTheSheet` (Show Welcome over Home, drawer's
 * `HomeFeature.welcome`); `storyTimeWaitsThenLoops`, `storyDictatesThenShowsTheScreenshotThenShipsIt`,
 * `metaSiteScreenshotIsDarkAndCarrierProgressIsMonotonic` and
 * `storyVoiceNeverTouchesTheLiveDictationMeter` (the animated story isn't ported yet).
 */
class WelcomeFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()

    @Test
    fun firstRunGetStartedThenScanRunsTheClassicPairing() = runTest {
        val store = shared.appStore(shared.appState(), detachedScope(coroutineContext))
        store.send(AppFeature.Action.Welcome(WelcomeFeature.Action.GetStartedTapped)) {
            it.copy(welcome = it.welcome.copy(step = WelcomeFeature.Step.pair))
        }
        store.send(AppFeature.Action.Welcome(WelcomeFeature.Action.ScanTapped))
        store.receive(AppFeature.Action.Welcome(WelcomeFeature.Action.Delegate(WelcomeFeature.Request.Scan))) {
            it.copy(isScanningPairingCode = true, connection = AppFeature.State.Connection.Picking)
        }
        store.send(AppFeature.Action.Welcome(WelcomeFeature.Action.BackTapped)) {
            it.copy(welcome = it.welcome.copy(step = WelcomeFeature.Step.intro))
        }
    }

    @Test
    fun enterAddressWaitsForOtherWaysToClose() = runTest {
        val state = shared.appState().let { it.copy(welcome = it.welcome.copy(step = WelcomeFeature.Step.pair, isShowingOtherWays = true)) }
        val store = shared.appStore(state, detachedScope(coroutineContext))
        store.send(AppFeature.Action.Welcome(WelcomeFeature.Action.EnterAddressTapped)) {
            it.copy(welcome = it.welcome.copy(isShowingOtherWays = false, pendingRequest = WelcomeFeature.Request.EnterAddress))
        }
        store.send(AppFeature.Action.Welcome(WelcomeFeature.Action.OtherWaysDismissed)) {
            it.copy(welcome = it.welcome.copy(pendingRequest = null))
        }
        store.receive(AppFeature.Action.Welcome(WelcomeFeature.Action.Delegate(WelcomeFeature.Request.EnterAddress))) {
            it.copy(isEditingServer = true, password = "", connection = AppFeature.State.Connection.Picking)
        }
    }
}
