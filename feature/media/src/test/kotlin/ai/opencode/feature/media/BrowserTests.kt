package ai.opencode.feature.media

import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.PageShareRequest
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * Port of iOS `BrowserTests.swift` (`BrowserLinkPolicyTests`). URLs are strings on Android.
 */
class BrowserLinkPolicyTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun publicWebLinksOpenInTheBrowserOnlyWhileItsOn() {
        val page = "https://developer.apple.com/documentation/webkit/webpage"
        assertEquals(BrowserLinkRoute.InAppBrowser(page), BrowserLinkPolicy.route(page, inAppBrowser = true))
        assertEquals(BrowserLinkRoute.System, BrowserLinkPolicy.route(page, inAppBrowser = false))
        assertEquals(
            BrowserLinkRoute.InAppBrowser("http://example.com/a?b=c"),
            BrowserLinkPolicy.route("http://example.com/a?b=c", inAppBrowser = true),
        )
    }

    @Test
    fun filesStillOpenInTheViewer() {
        val file = AgentFileReference(path = "/project/README.md")
        val link = file.linkURL
        assertEquals(BrowserLinkRoute.File(AgentFileReference.fromLinkURL(link)!!), BrowserLinkPolicy.route(link, inAppBrowser = true))
        assertEquals(BrowserLinkRoute.File(AgentFileReference.fromLinkURL(link)!!), BrowserLinkPolicy.route(link, inAppBrowser = false))
    }

    @Test
    fun privateSignedAndOtherLinksKeepOpeningAsBefore() {
        for (link in listOf(
            "mailto:kit@example.com", "tel:+15551234567", "file:///etc/hosts", "itms-apps://apps.apple.com/app/id1",
            "http://localhost:4096", "http://127.0.0.1:4110/lab/page", "http://192.168.1.20/admin", "http://10.0.0.5",
            "http://172.20.1.1", "http://100.101.102.103", "http://169.254.1.1", "http://[::1]:8080", "http://[fd00::1]/",
            "https://kits-shiny-computer.taila8e7c7.ts.net:4790/", "http://printer.local/", "http://intranet/",
            "https://user:secret@example.com/", "https://bucket.s3.amazonaws.com/file?X-Amz-Signature=abc",
            "https://example.com/download?token=abc", "https://storage.example.com/blob?sv=2024&sig=abc",
            "http://localhost./", "http://127.1/", "http://2130706433/", "http://[::ffff:127.0.0.1]/", "http://[::ffff:7f00:1]/",
            "https://nas.ts.net./", "http://8.8.8.8/",
        )) {
            assertEquals(link, BrowserLinkRoute.System, BrowserLinkPolicy.route(link, inAppBrowser = true))
        }
    }

    @Test
    fun aTrailingDotAndPortsStillReachPublicPages() {
        assertTrue(BrowserLinkPolicy.isPublicWebPage("https://example.com./docs", allowsLoopback = false))
        assertTrue(BrowserLinkPolicy.isPublicWebPage("https://example.com:8443/", allowsLoopback = false))
    }

    @Test
    fun pagesNeverLoadPrivateOrScriptedNavigations() {
        val policy = BrowserNavigationPolicy
        val allow = BrowserNavigationPolicy.Decision.Allow
        val cancel = BrowserNavigationPolicy.Decision.Cancel
        val page = "https://example.com/next"
        assertEquals(allow, policy.decide(page, isMainFrame = true, isUserTap = true, allowsLoopback = false))
        assertEquals(allow, policy.decide(page, isMainFrame = false, isUserTap = false, allowsLoopback = false))
        // Frames: never a private host, a credential, or another app, and never the system.
        for (link in listOf("http://localhost:4096/", "http://192.168.1.1/", "https://box.ts.net/", "https://u:p@example.com/", "mailto:a@b.c")) {
            assertEquals(link, cancel, policy.decide(link, isMainFrame = false, isUserTap = true, allowsLoopback = false))
        }
        // The page itself: a tapped link leaves for the system; a script or redirect is stopped.
        val lan = "http://192.168.1.1/"
        assertEquals(BrowserNavigationPolicy.Decision.OpenInSystem(lan), policy.decide(lan, isMainFrame = true, isUserTap = true, allowsLoopback = false))
        assertEquals(cancel, policy.decide(lan, isMainFrame = true, isUserTap = false, allowsLoopback = false))
        assertEquals(cancel, policy.decide("tel:+15551234567", isMainFrame = true, isUserTap = false, allowsLoopback = false))
        // Pairing links never open in the browser or load in the page; a main-frame tap routes to pairing confirmation.
        for (pairing in listOf(
            "https://opencode.abc123.opentunnel.xyz/auth/connect/Ab3_x-9Q",
            "https://opencode.abc123.opentunnel.xyz/auth/connect/Ab3_x-9Q#frag",
            "opencode-ios://pair?code=Ab3_x-9Q&url=https://mini-trial.taila8e7c7.ts.net:4196",
        )) {
            assertEquals(pairing, BrowserNavigationPolicy.Decision.Pair(pairing), policy.decide(pairing, isMainFrame = true, isUserTap = true, allowsLoopback = false))
            assertEquals(pairing, cancel, policy.decide(pairing, isMainFrame = true, isUserTap = false, allowsLoopback = false))
            assertEquals(pairing, cancel, policy.decide(pairing, isMainFrame = false, isUserTap = true, allowsLoopback = false))
        }
        // Top-level data and blob pages never load; about:blank does, and frames may use them.
        assertEquals(cancel, policy.decide("data:text/html,hi", isMainFrame = true, isUserTap = true, allowsLoopback = false))
        assertEquals(cancel, policy.decide("blob:https://example.com/1", isMainFrame = true, isUserTap = false, allowsLoopback = false))
        assertEquals(allow, policy.decide("data:text/html,hi", isMainFrame = false, isUserTap = false, allowsLoopback = false))
        assertEquals(allow, policy.decide("about:blank", isMainFrame = true, isUserTap = false, allowsLoopback = false))
        assertEquals(cancel, policy.decide("about:config", isMainFrame = true, isUserTap = false, allowsLoopback = false))
    }

    @Test
    fun downloadsAndUnshowableResponsesGoToTheSystem() {
        assertTrue(BrowserNavigationPolicy.handsToSystem(canShow = false, disposition = null))
        assertTrue(BrowserNavigationPolicy.handsToSystem(canShow = true, disposition = "attachment; filename=report.pdf"))
        assertFalse(BrowserNavigationPolicy.handsToSystem(canShow = true, disposition = "inline"))
        assertFalse(BrowserNavigationPolicy.handsToSystem(canShow = true, disposition = null))
    }

    @Test
    fun theLabCanAllowLoopbackPages() {
        assertTrue(BrowserLinkPolicy.isPublicWebPage("http://127.0.0.1:4110/lab/page/article", allowsLoopback = true))
        assertFalse(BrowserLinkPolicy.isPublicWebPage("http://192.168.1.2/", allowsLoopback = true))
    }
}

/** Port of iOS `BrowserFeatureTests` (`BrowserTests.swift`). */
class BrowserFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    private val article = "https://www.fastcompany.com/91425207/x-links"
    private fun uuid(n: Long) = UUID(0, n)

    @Test
    fun aPagePushesGoesBackAndForwardAndCloses() = runTest {
        val back = Locked(0)
        val forward = Locked(0)
        val discarded = Locked(listOf<UUID>())
        val store = TestStore(BrowserFeature.State(), BrowserFeature(), this) {
            this[UuidClient] = UuidClient.incrementing()
            this[PageReaderClient] = PageReaderClient(
                read = PageReaderClient.testValue.read,
                goBack = { back.update { it + 1 } },
                goForward = { forward.update { it + 1 } },
                discard = { id -> discarded.update { it + id } },
            )
        }
        val id = uuid(0)
        store.send(BrowserFeature.Action.Open(article)) {
            it.copy(pageID = id, openedURL = article, url = article, isLoading = true)
        }
        assertTrue(store.state.isOpen)
        assertEquals("fastcompany.com", store.state.host)
        assertEquals("The host stands in until the title loads", "fastcompany.com", store.state.displayTitle)
        store.send(BrowserFeature.Action.Navigated(url = article, title = "X’s UX update")) { it.copy(title = "X’s UX update") }
        store.send(BrowserFeature.Action.LoadingChanged(false)) { it.copy(isLoading = false) }

        // Nothing to go back or forward to yet.
        store.send(BrowserFeature.Action.BackTapped)
        store.send(BrowserFeature.Action.ForwardTapped)
        store.send(BrowserFeature.Action.CanGoBackChanged(true)) { it.copy(canGoBack = true) }
        store.send(BrowserFeature.Action.BackTapped)
        store.send(BrowserFeature.Action.CanGoForwardChanged(true)) { it.copy(canGoForward = true) }
        store.send(BrowserFeature.Action.ForwardTapped)
        store.finish()
        assertEquals(1, back.current)
        assertEquals(1, forward.current)

        store.send(BrowserFeature.Action.CloseTapped) { BrowserFeature.State() }
        store.finish()
        assertEquals(listOf(id), discarded.current)
    }

    @Test
    fun theSameLinkKeepsThePageAndANewOneReplacesIt() = runTest {
        val discarded = Locked(listOf<UUID>())
        val store = TestStore(BrowserFeature.State(), BrowserFeature(), this) {
            this[UuidClient] = UuidClient.incrementing()
            this[PageReaderClient] = PageReaderClient(
                read = PageReaderClient.testValue.read,
                goBack = {},
                goForward = {},
                discard = { id -> discarded.update { it + id } },
            )
        }
        val redirected = "https://www.fastcompany.com/91425207/x-links/"
        store.send(BrowserFeature.Action.Open(article)) {
            it.copy(pageID = uuid(0), openedURL = article, url = article, isLoading = true)
        }
        store.send(BrowserFeature.Action.Navigated(url = redirected, title = "X")) { it.copy(url = redirected, title = "X") }
        store.send(BrowserFeature.Action.Open("https://WWW.fastcompany.com/91425207/x-links?utm_source=x#top"))
        assertEquals("The same visit, not a reload", uuid(0), store.state.pageID)
        val other = "https://developer.apple.com/documentation/webkit/webpage"
        store.send(BrowserFeature.Action.Open(other)) {
            it.copy(pageID = uuid(1), openedURL = other, url = other, title = "", isLoading = true)
        }
        store.finish()
        assertEquals(listOf(uuid(0)), discarded.current)
    }

    @Test
    fun askRaisesAComposerAndOnlyAnOpenPageIsShared() = runTest {
        val store = TestStore(BrowserFeature.State(), BrowserFeature(), this) {
            this[UuidClient] = UuidClient.incrementing()
        }
        store.send(BrowserFeature.Action.AskTapped)
        assertNull(store.state.shareRequest)
        assertNull(store.state.viewing)
        store.send(BrowserFeature.Action.Open(article)) {
            it.copy(pageID = uuid(0), openedURL = article, url = article, isLoading = true)
        }
        assertEquals(
            PageShareRequest(pageID = uuid(0), url = article, title = "fastcompany.com", includesScreenshot = false),
            store.state.shareRequest,
        )
        assertEquals(article, store.state.viewing?.url)
        store.send(BrowserFeature.Action.AskTapped) { it.copy(isAsking = true) }
        store.send(BrowserFeature.Action.AskDraftChanged("Summarise this")) { it.copy(askDraft = "Summarise this") }
        store.send(BrowserFeature.Action.AskDismissed) { it.copy(isAsking = false) }
        store.finish()
    }
}

/**
 * Port of iOS `LinkMenuTests` (`BrowserTests.swift`): what the menu offers. The menu actions'
 * effects run in `SessionFeature` (`:feature:session`); WebKit's context-menu configuration has
 * no Android counterpart (the WebView's hold menu is ours, see `BrowserWebViews.linkMenuActions`).
 */
class LinkMenuTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun holdingALinkOffersSafariCopyAndShare() {
        val url = "https://example.com/post"
        assertEquals(
            listOf(
                LinkMenuAction.OpenInAppBrowser, LinkMenuAction.OpenInSafari, LinkMenuAction.OpenInApp, LinkMenuAction.Copy,
                LinkMenuAction.Share, LinkMenuAction.OpenSession, LinkMenuAction.CopySessionID,
            ),
            LinkMenuAction.entries.toList(),
        )
        assertEquals(
            "Public links offer Open in Browser, Open in App, Copy Link, and Share",
            listOf(LinkMenuAction.OpenInSafari, LinkMenuAction.OpenInApp, LinkMenuAction.Copy, LinkMenuAction.Share),
            LinkMenuAction.actions(url, ExperimentalFeatures.testValue),
        )

        // Pairing URLs never offer the browser or the in-app browser, and copy/share the custom-scheme deep link.
        val pairingURL = "https://mini-trial.taila8e7c7.ts.net:4196/auth/connect/Ab3_x-9Q"
        val expectedShareURL = "opencode-ios://pair?code=Ab3_x-9Q&url=https://mini-trial.taila8e7c7.ts.net:4196"
        val browserOn = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.inAppBrowser))
        assertTrue(LinkMenuAction.offers(pairingURL))
        assertTrue(LinkMenuAction.offers(expectedShareURL))
        assertEquals(listOf(LinkMenuAction.Copy, LinkMenuAction.Share), LinkMenuAction.actions(pairingURL, browserOn))
        assertEquals(listOf(LinkMenuAction.Copy, LinkMenuAction.Share), LinkMenuAction.actions(expectedShareURL, browserOn))
        assertEquals(expectedShareURL, OpenCodePairingLink.shareableURL(pairingURL))

        val malformedPairingURL = "https://mini-trial.taila8e7c7.ts.net:4196/auth/connect/Ab3_x-9Q?extra=1"
        assertFalse(LinkMenuAction.offers(malformedPairingURL))
        assertTrue(LinkMenuAction.actions(malformedPairingURL, browserOn).isEmpty())
    }

    @Test
    fun onlyWebLinksGetTheMenu() {
        assertTrue(LinkMenuAction.offers("https://example.com"))
        assertFalse(LinkMenuAction.offers("opencode-file:///project/a.swift"))
        val docs = "https://example.com/docs"
        val post = "https://x.com/post"
        val text = buildAnnotatedString {
            append("Read the ")
            withLink(LinkAnnotation.Url(docs)) { append("docs") }
            append(" and the ")
            withLink(LinkAnnotation.Url(post)) { append("post") }
            append(" and the docs again.")
        }
        assertEquals(listOf(docs, post), LinkMenuText.webLinks(text))
        assertEquals("Copy Link", LinkMenuText.accessibilityName(LinkMenuAction.Copy, url = docs, among = listOf(docs)))
        assertEquals("Share Link to example.com", LinkMenuText.accessibilityName(LinkMenuAction.Share, url = docs, among = listOf(docs, post)))
    }
}

/**
 * The menu half of iOS `OpenLinksInAppsTests.theMenuOffersTheAppBrowserOnlyWhenATapMightSkipIt`;
 * its universal-link probe runs in `SessionFeature` (`:feature:session`).
 */
class OpenLinksInAppsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val link = "https://github.com/anomalyco/opencode/pull/1"
    private val other = "https://example.com/next"

    @Test
    fun theMenuOffersTheAppBrowserOnlyWhenATapMightSkipIt() {
        val browserOn = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.inAppBrowser))
        assertEquals(
            listOf(LinkMenuAction.OpenInAppBrowser, LinkMenuAction.OpenInSafari, LinkMenuAction.OpenInApp, LinkMenuAction.Copy, LinkMenuAction.Share),
            LinkMenuAction.actions(link, browserOn),
        )
        assertEquals(
            listOf(LinkMenuAction.OpenInSafari, LinkMenuAction.OpenInApp, LinkMenuAction.Copy, LinkMenuAction.Share),
            LinkMenuAction.actions(link, ExperimentalFeatures.enabling(emptySet())),
        )
        assertEquals(
            listOf(LinkMenuAction.OpenInSafari, LinkMenuAction.Copy, LinkMenuAction.Share),
            LinkMenuAction.actions("http://192.168.1.20/", browserOn),
        )
        assertEquals("Open in App Browser", LinkMenuText.accessibilityName(LinkMenuAction.OpenInAppBrowser, url = link, among = listOf(link)))
        assertEquals("Open in App", LinkMenuText.accessibilityName(LinkMenuAction.OpenInApp, url = link, among = listOf(link)))
        assertEquals("Open github.com in App", LinkMenuText.accessibilityName(LinkMenuAction.OpenInApp, url = link, among = listOf(link, other)))
    }
}

/** Port of iOS `BrowserObscuredInsetsTests` (`BrowserWebViewHost.obscuredInsets` → [BrowserInsets.obscured]). */
class BrowserObscuredInsetsTests {
    @Test
    fun negativeInsetsNeverReachTheWebView() {
        val insets = BrowserInsets.obscured(BrowserInsets(top = -3f, left = -59f, bottom = 120f, right = -0.5f))
        assertEquals(BrowserInsets(top = 0f, left = 0f, bottom = 120f, right = 0f), insets)
    }

    @Test
    fun positiveInsetsPassThroughUnchanged() {
        val insets = BrowserInsets(top = 108f, left = 59f, bottom = 92f, right = 59f)
        assertEquals(insets, BrowserInsets.obscured(insets))
    }
}
