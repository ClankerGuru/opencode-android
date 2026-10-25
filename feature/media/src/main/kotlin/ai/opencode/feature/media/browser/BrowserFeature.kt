package ai.opencode.feature.media

import ai.opencode.core.dependencies.Dependency
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.server.model.PageShareRequest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.IDN
import java.util.UUID

/**
 * The in-app browser (Experimental → In-App Browser): a web page pushed onto the session's stack
 * like a detail screen (D-40, Push In). Its bar goes back and forward through the page's
 * history, shares it, opens it in the browser, and asks about it: the question goes to the
 * session with the page attached, and a capsule says when the reply is ready. Going back from
 * the first page (the back button, the back gesture, TalkBack's back) closes it. The web view
 * itself lives in [BrowserWebViews], keyed by `pageID`. Port of iOS `BrowserFeature`.
 */
class BrowserFeature : Reducer<BrowserFeature.State, BrowserFeature.Action> {
    data class State(
        /** One browser visit's web view. A new link starts a new one. */
        val pageID: UUID? = null,
        /** The link that opened this visit, and where the page is now. */
        val openedURL: String? = null,
        val url: String? = null,
        val title: String = "",
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val isLoading: Boolean = false,
        /** The Ask composer over the bar, and what's typed in it. */
        val isAsking: Boolean = false,
        val askDraft: String = "",
        /** The last question asked from this page; its reply shows in the capsule. */
        val peekMessageID: String? = null,
    ) {
        val isOpen: Boolean get() = pageID != null

        /**
         * Whether [url] is this visit's page, compared without tracking parameters, fragments,
         * or a trailing slash.
         */
        fun shows(url: String): Boolean {
            val key = LinkPreviewPolicy.normalized(url) ?: url
            return listOf(openedURL, this.url).any { candidate ->
                candidate != null && (LinkPreviewPolicy.normalized(candidate) ?: candidate) == key
            }
        }

        val host: String
            get() {
                val host = url?.let(::browserHost) ?: return ""
                return if (host.startsWith("www.")) host.drop(4) else host
            }

        /** The title the bar shows: the page's, or its host while loading. */
        val displayTitle: String get() = title.trim().ifEmpty { host }

        /** What a question from here asks the page reader for. */
        val shareRequest: PageShareRequest?
            get() {
                val pageID = pageID ?: return null
                val url = url ?: return null
                return PageShareRequest(pageID = pageID, url = url, title = displayTitle, includesScreenshot = false)
            }

        /** What the voice agent is told you're looking at. */
        val viewing: VoiceViewingPage?
            get() {
                val url = url ?: return null
                if (pageID == null) return null
                return VoiceViewingPage(url = url, title = displayTitle, isOpen = true)
            }
    }

    sealed interface Action {
        /** A public web link tapped in a reply. */
        data class Open(val url: String) : Action
        /** Back from the first page: the page leaves the stack. */
        data object CloseTapped : Action
        data object BackTapped : Action
        data object ForwardTapped : Action
        data object AskTapped : Action
        data object AskDismissed : Action
        data class AskDraftChanged(val text: String) : Action
        /** Ask's Send. `SessionFeature` sends the draft with the page attached. */
        data object AskSent : Action
        data class Navigated(val url: String, val title: String) : Action
        data class TitleChanged(val title: String) : Action
        data class LoadingChanged(val loading: Boolean) : Action
        data class CanGoBackChanged(val canGoBack: Boolean) : Action
        data class CanGoForwardChanged(val canGoForward: Boolean) : Action
        data class PairingLinkTapped(val url: String) : Action
        data class LinkMenu(val action: LinkMenuAction, val url: String) : Action
    }

    private val uuid by Dependency(UuidClient)
    private val pageReader by Dependency(PageReaderClient)

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        is Action.Open -> {
            // The same page (a link card or the link again) is already on top.
            if (state.isOpen && state.shows(action.url)) {
                Reduced(state)
            } else {
                val previous = state.pageID
                Reduced(
                    State(pageID = uuid.next(), openedURL = action.url, url = action.url, isLoading = true),
                    discard(previous),
                )
            }
        }

        Action.CloseTapped -> Reduced(State(), discard(state.pageID))

        Action.BackTapped -> {
            val id = state.pageID
            if (id == null || !state.canGoBack) Reduced(state)
            else pageReader.let { reader -> Reduced(state, Effect.run { reader.goBack(id) }) }
        }

        Action.ForwardTapped -> {
            val id = state.pageID
            if (id == null || !state.canGoForward) Reduced(state)
            else pageReader.let { reader -> Reduced(state, Effect.run { reader.goForward(id) }) }
        }

        Action.AskTapped -> if (!state.isOpen) Reduced(state) else Reduced(state.copy(isAsking = true))

        Action.AskDismissed -> Reduced(state.copy(isAsking = false))

        is Action.AskDraftChanged -> Reduced(state.copy(askDraft = action.text))

        Action.AskSent -> Reduced(state)

        is Action.Navigated ->
            if (!state.isOpen) Reduced(state) else Reduced(state.copy(url = action.url, title = action.title))

        is Action.TitleChanged -> Reduced(state.copy(title = action.title))

        is Action.LoadingChanged -> Reduced(state.copy(isLoading = action.loading))

        is Action.CanGoBackChanged -> Reduced(state.copy(canGoBack = action.canGoBack))

        is Action.CanGoForwardChanged -> Reduced(state.copy(canGoForward = action.canGoForward))

        is Action.PairingLinkTapped, is Action.LinkMenu -> Reduced(state)
    }

    private fun discard(id: UUID?): Effect<Action> {
        id ?: return Effect.none()
        val pageReader = pageReader
        return Effect.run { pageReader.discard(id) }
    }
}

/**
 * The page the voice agent hears about (iOS `VoiceViewingPage`). Declared in `:server` so the call
 * (`:feature:voice`) reads the same type the browser produces.
 */
typealias VoiceViewingPage = ai.opencode.server.model.VoiceViewingPage

/** `url.host(percentEncoded: false)` for web URLs: the host, Unicode rather than punycode. */
internal fun browserHost(url: String): String? {
    val host = url.toHttpUrlOrNull()?.host ?: return null
    return runCatching { IDN.toUnicode(host) }.getOrDefault(host)
}
